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

def _load_env():
    """同目录 .env 里补环境变量（已有的不覆盖）。Kotlin 起进程时不 export，靠这里兜住。"""
    try:
        p = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
        with open(p, encoding="utf-8", errors="ignore") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip()
                v = v.strip().strip('"').strip("'")
                if k and k not in os.environ:
                    os.environ[k] = v
    except Exception:
        pass


_load_env()

BASE = os.environ.get("AJ_BASE", "https://open.bigmodel.cn/api/paas/v4").rstrip("/")
MODEL = os.environ.get("AJ_MODEL", "glm-4.6")
KEY = os.environ.get("AJ_KEY", "")
PORT = int(os.environ.get("AJ_BROKER_PORT", "8788"))
RETRY = (429, 500, 502, 503, 504)
FATAL = (401, 402, 403, 404)


def _models():
    """降级链：AJ_MODELS=a,b,c（第一个是主力）。没配就一个。"""
    raw = (os.environ.get("AJ_MODELS") or "").strip()
    out = [m.strip() for m in raw.split(",") if m.strip()] if raw else []
    if MODEL and MODEL not in out:
        out.insert(0, MODEL)
    return out or [MODEL]


def _send_json_with_model(handler, code, obj, model):
    b = json.dumps(obj, ensure_ascii=False).encode()
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("X-AJ-Model", model or "")
    handler.send_header("Content-Length", str(len(b)))
    handler.end_headers()
    handler.wfile.write(b)


def _draw(prompt, model="", size="1024x1024"):
    """出图：走当前服务商的 /images/generations（智谱 cogview 之类）。
    key 只在这里用，返回 {"ok":True,"b64":...} 或 {"ok":False,"error":...}。"""
    import base64, json as _j
    base = (os.environ.get("AJ_BASE") or "").rstrip("/")
    key = os.environ.get("AJ_KEY") or ""
    if not base or not key:
        return {"ok": False, "error": "没有 key / 地址：父 AJ 没把配置写过来"}
    m = model or os.environ.get("AJ_DRAW_MODEL") or "cogview-3-flash"
    body = _j.dumps({"model": m, "prompt": prompt, "size": size}).encode()
    rq = urllib.request.Request(base + "/images/generations", data=body, method="POST",
                                headers={"Authorization": "Bearer " + key,
                                         "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(rq, timeout=180) as r:
            d = _j.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        try:
            d = _j.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            return {"ok": False, "error": "HTTP %s" % e.code}
        msg = str((d.get("error") or {}).get("message") if isinstance(d.get("error"), dict) else d.get("error") or d)[:200]
        return {"ok": False, "error": "HTTP %s %s" % (e.code, msg)}
    except Exception as e:
        return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}
    items = d.get("data") or []
    if not items:
        return {"ok": False, "error": "没有返回图片：%s" % str(d)[:200]}
    it = items[0]
    if it.get("b64_json"):
        return {"ok": True, "b64": it["b64_json"], "model": m}
    if it.get("url"):
        try:
            with urllib.request.urlopen(it["url"], timeout=120) as r:
                return {"ok": True, "b64": base64.b64encode(r.read()).decode(), "model": m}
        except Exception as e:
            return {"ok": False, "error": "下载图片失败：%s" % e}
    return {"ok": False, "error": "返回里没有图片数据"}



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
        path = self.path.split("?")[0]
        if path not in ("/chat", "/draw"):
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
        if path == "/draw":
            r = _draw(str(body.get("prompt") or ""), str(body.get("model") or ""),
                      str(body.get("size") or "1024x1024"))
            return self._json(200 if r.get("ok") else 502, r)
        if not KEY:
            return self._json(400, {"error": "没有 key：在 .env 里填 AJ_KEY 再启动"})
        picked = (body.get("model") or "").strip()
        chain = [picked] if picked else _models()
        last = "未知错误"
        tried = []
        for model in chain:
            tried.append(model)
            req = {"model": model, "messages": body.get("messages") or []}
            for k in ("temperature", "max_tokens", "stream"):
                if k in body:
                    req[k] = body[k]
            data = json.dumps(req).encode()
            for attempt in range(3):
                rq = urllib.request.Request(
                    BASE + "/chat/completions",
                    data=data,
                    headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY},
                )
                try:
                    with urllib.request.urlopen(rq, timeout=180) as r:
                        return _send_json_with_model(self, 200, json.loads(r.read().decode()), model)
                except urllib.error.HTTPError as e:
                    txt = e.read().decode(errors="replace")[:300]
                    last = "HTTP %s %s" % (e.code, txt)
                    if e.code in FATAL:
                        break          # 这个模型不可用，换下一个
                    if e.code in RETRY:
                        if attempt < 2:
                            time.sleep(2 * (attempt + 1))
                            continue
                        break          # 退避用完，换下一个
                    break
                except Exception as e:
                    last = "连不上模型服务：%s" % type(e).__name__
                    if attempt < 2:
                        time.sleep(2 * (attempt + 1))
                        continue
                    break
        return _send_json_with_model(self, 502, {"error": last, "tried": tried}, "")



def _write_pid(name):
    try:
        open(os.path.join(os.path.dirname(os.path.abspath(__file__)), name), "w").write(str(os.getpid()))
    except Exception:
        pass


if __name__ == "__main__":
    _write_pid("broker.pid")
    print("broker on 127.0.0.1:%d  model=%s  key=%s" % (PORT, MODEL, "已配置" if KEY else "未配置"))
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
