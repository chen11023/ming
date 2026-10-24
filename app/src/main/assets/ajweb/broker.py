#!/usr/bin/env python3
"""key 中间人：网页和模型之间的唯一出口。
key 只从环境变量读，日志、响应、错误信息里一律不出现 key。
"""
import json
import os
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.environ.get("AJ_BASE", "https://open.bigmodel.cn/api/paas/v4").rstrip("/")
MODEL = os.environ.get("AJ_MODEL", "glm-4.6")
KEY = os.environ.get("AJ_KEY", "")
PORT = int(os.environ.get("AJ_BROKER_PORT", "8788"))
RETRY = (429, 500, 502, 503, 504)
FATAL = (401, 402, 403, 404)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.split("?")[0] == "/health":
            return self._json(200, {"ok": True, "model": MODEL, "has_key": bool(KEY)})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path.split("?")[0] != "/chat":
            return self._json(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n > 4_000_000:
            return self._json(413, {"error": "请求太大"})
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return self._json(400, {"error": "不是合法 JSON"})
        if not KEY:
            return self._json(400, {"error": "没有 key：在 .env 里填 AJ_KEY 再启动"})
        req = {"model": body.get("model") or MODEL, "messages": body.get("messages") or []}
        for k in ("temperature", "max_tokens", "stream"):
            if k in body:
                req[k] = body[k]
        data = json.dumps(req).encode()
        last = "未知错误"
        for attempt in range(3):
            rq = urllib.request.Request(
                BASE + "/chat/completions",
                data=data,
                headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY},
            )
            try:
                with urllib.request.urlopen(rq, timeout=180) as r:
                    return self._json(200, json.loads(r.read().decode()))
            except urllib.error.HTTPError as e:
                txt = e.read().decode(errors="replace")[:300]
                last = "HTTP %s %s" % (e.code, txt)
                if e.code in FATAL:
                    break
                if e.code in RETRY:
                    time.sleep(2 * (attempt + 1))
                    continue
                break
            except Exception as e:
                last = "连不上模型服务：%s" % type(e).__name__
                time.sleep(2 * (attempt + 1))
        return self._json(502, {"error": last})


if __name__ == "__main__":
    print("broker on 127.0.0.1:%d  model=%s  key=%s" % (PORT, MODEL, "已配置" if KEY else "未配置"))
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
