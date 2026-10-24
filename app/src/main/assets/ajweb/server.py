#!/usr/bin/env python3
"""子 AJ：一个网页，电脑/平板/手机浏览器打开就能问它、让它跑命令。
模型 key 不在这个进程里，全部请求走 broker（127.0.0.1:8788）。
"""
import json
import os
import re
import secrets
import subprocess
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = int(os.environ.get("AJ_WEB_PORT", "8787"))
BROKER = os.environ.get("AJ_BROKER", "http://127.0.0.1:8788")
TOKEN = os.environ.get("AJ_WEB_TOKEN", "").strip()
MAX_ROUND = 6
SH_TIMEOUT = 120

SYSTEM = """你是一台安卓手机里的小助手，运行在完整的 Alpine Linux 环境里。
用户从网页跟你说话，你能用命令操作这台机器。

回复格式（严格）：
- 要执行命令：<sh>命令</sh>  一次只放一条，等结果回来再继续
- 给出最终答案：<done>答案正文</done>

规矩：
1. 不知道就先查：ls、rg、cat、ss、curl --help，别猜。
2. 危险操作（rm -rf、改系统配置、装大包）先说清要做什么，再等用户确认。
3. 结论要带依据，命令输出里能看到的就贴关键行。
4. 不要编造命令输出。
"""

if not TOKEN:
    TOKEN = secrets.token_urlsafe(9)


def auth(h):
    q = h.path.split("?", 1)[1] if "?" in h.path else ""
    for part in q.split("&"):
        if part.startswith("k=") and part[2:] == TOKEN:
            return True
    if h.headers.get("X-AJ-Token") == TOKEN:
        return True
    ck = h.headers.get("Cookie") or ""
    return ("ajtoken=" + TOKEN) in ck


def model_chat(messages):
    body = json.dumps({"messages": messages, "temperature": 0.3}).encode()
    rq = urllib.request.Request(
        BROKER + "/chat", data=body, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(rq, timeout=200) as r:
            data = json.loads(r.read().decode())
        ch = (data.get("choices") or [{}])[0]
        return (ch.get("message") or {}).get("content") or ""
    except urllib.error.HTTPError as e:
        try:
            return "[模型出错] " + json.loads(e.read().decode()).get("error", str(e.code))
        except Exception:
            return "[模型出错] HTTP %s" % e.code
    except Exception as e:
        return "[模型出错] %s" % type(e).__name__


def run_sh(cmd):
    try:
        p = subprocess.run(
            ["sh", "-c", cmd], capture_output=True, timeout=SH_TIMEOUT,
            cwd=os.path.expanduser("~"),
        )
        out = (p.stdout + p.stderr).decode(errors="replace")
        out = out.strip()
        if len(out) > 6000:
            out = out[:6000] + "\n…（截断）"
        return "exit=%d\n%s" % (p.returncode, out or "(无输出)")
    except subprocess.TimeoutExpired:
        return "超时（%d 秒），命令还在跑或已卡住" % SH_TIMEOUT
    except Exception as e:
        return "执行失败：%s" % e


def agent_loop(text, history):
    msgs = [{"role": "system", "content": SYSTEM}]
    for m in history[-12:]:
        if m.get("role") in ("user", "assistant") and m.get("content"):
            msgs.append({"role": m["role"], "content": str(m["content"])[:4000]})
    msgs.append({"role": "user", "content": text})
    events = []
    for _ in range(MAX_ROUND):
        reply = model_chat(msgs)
        if reply.startswith("[模型出错]"):
            events.append({"type": "error", "text": reply})
            break
        cmds = re.findall(r"<sh>(.*?)</sh>", reply, re.S)
        done = re.search(r"<done>(.*?)</done>", reply, re.S)
        said = re.sub(r"<sh>.*?</sh>", "", reply, flags=re.S)
        said = re.sub(r"<done>.*?</done>", "", said, flags=re.S).strip()
        if said:
            events.append({"type": "say", "text": said[:4000]})
        if cmds:
            cmd = cmds[0].strip()
            events.append({"type": "cmd", "text": cmd})
            out = run_sh(cmd)
            events.append({"type": "out", "text": out})
            msgs.append({"role": "assistant", "content": reply})
            msgs.append({"role": "user", "content": "命令结果：\n" + out})
            continue
        if done:
            events.append({"type": "done", "text": done.group(1).strip()[:6000]})
            break
        events.append({"type": "done", "text": reply[:6000]})
        break
    else:
        events.append({"type": "error", "text": "轮数用完了（%d 轮），可能卡在反复执行" % MAX_ROUND})
    return events


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        b = body if isinstance(body, bytes) else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/index.html"):
            if not auth(self):
                return self._send(401, "需要口令：在地址后面加 ?k=你的口令", "text/plain; charset=utf-8")
            page = os.path.join(HERE, "index.html")
            with open(page, "rb") as f:
                return self._send(
                    200, f.read(), "text/html; charset=utf-8",
                    {"Set-Cookie": "ajtoken=%s; Path=/; HttpOnly" % TOKEN},
                )
        if path == "/health":
            return self._send(200, json.dumps({"ok": True}))
        self._send(404, "not found", "text/plain")

    def do_POST(self):
        if not auth(self):
            return self._send(401, json.dumps({"error": "口令不对"}))
        if self.path.split("?")[0] != "/api/chat":
            return self._send(404, json.dumps({"error": "not found"}))
        n = int(self.headers.get("Content-Length") or 0)
        if n > 500_000:
            return self._send(413, json.dumps({"error": "太大"}))
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return self._send(400, json.dumps({"error": "bad json"}))
        text = (body.get("text") or "").strip()
        if not text:
            return self._send(400, json.dumps({"error": "空消息"}))
        events = agent_loop(text, body.get("history") or [])
        self._send(200, json.dumps({"events": events}, ensure_ascii=False))


if __name__ == "__main__":
    print("网页子 AJ: http://0.0.0.0:%d/?k=%s" % (PORT, TOKEN))
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
