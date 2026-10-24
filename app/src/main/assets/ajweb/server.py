#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AJ 的网页聊天壳（App assets 版）。

跑在 App 解压出来的 assets 里：python3 /host/web/server.py
浏览器打开 http://<手机IP>:8787/?k=<AJ_WEB_TOKEN> 就能对话。

和沙箱版的区别：
  * 监听 0.0.0.0:AJ_WEB_PORT（默认 8787），局域网可访问
  * 口令走 AJ_WEB_TOKEN：?k= / X-AJ-Token / Cookie(ajtoken) 三种都认
  * 家目录是 AJ_HOME（默认 /host/ajhome），没有 /var/minis、没有 /sdcard
  * 模型只走本机 broker（127.0.0.1:8788），本文件不出现任何 key
"""
import base64
import io
import json
import os
import re
import secrets
import sys
import threading
import time
import traceback
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))


def _load_env():
    """App 把服务商配置写在同一目录的 .env（600），但它是 shell 起进程前没 export 的。
    这里读进 os.environ（环境变量优先），只在内存里用，绝不打印、绝不回显。"""
    try:
        with io.open(os.path.join(HERE, ".env"), encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip()
                v = v.strip().strip('"').strip("'")
                if k and k not in os.environ:
                    os.environ[k] = v
    except FileNotFoundError:
        pass
    except Exception:
        pass


_load_env()
sys.path.insert(0, HERE)
import agent as A

UP_D = os.path.join(A.AGENTD, "uploads")
INSTANCE_ID = os.environ.get("INSTANCE_ID", str(os.getpid()))

PORT = int(os.environ.get("AJ_WEB_PORT", "8787"))
HOST = os.environ.get("AJ_WEB_HOST", "0.0.0.0")
TOKEN = (os.environ.get("AJ_WEB_TOKEN") or "").strip() or secrets.token_urlsafe(9)
SESS_DIR = os.path.join(A.AGENTD, "sessions")
os.makedirs(SESS_DIR, exist_ok=True)
os.makedirs(UP_D, exist_ok=True)

LOCK = threading.Lock()
SESSIONS = {}          # sid -> {"msgs":[...], "task":..., "status":..., "log":[...]}
PENDING = {}           # sid -> threading.Event (等授权)


def _token_ok(h):
    """三种口令来源：?k= 查询参数、X-AJ-Token 请求头、Cookie ajtoken。"""
    try:
        if h.headers.get("X-AJ-Token") == TOKEN:
            return True
    except Exception:
        pass
    try:
        p = h.path.split("?", 1)
        if len(p) > 1:
            for part in p[1].split("&"):
                k, _, v = part.partition("=")
                if k == "k" and v == TOKEN:
                    return True
    except Exception:
        pass
    ck = h.headers.get("Cookie") or ""
    for part in ck.split(";"):
        k, _, v = part.strip().partition("=")
        if k == "ajtoken" and v == TOKEN:
            return True
    return False


def _q_token(h):
    p = h.path.split("?", 1)
    if len(p) < 2:
        return False
    for part in p[1].split("&"):
        k, _, v = part.partition("=")
        if k == "k" and v == TOKEN:
            return True
    return False


def sess_path(sid):
    return os.path.join(SESS_DIR, f"{sid}.json")


def save(sid):
    try:
        with open(sess_path(sid), "w") as f:
            json.dump(SESSIONS[sid], f, ensure_ascii=False)
    except Exception:
        pass


def _save_turn(s, task, out):
    """只记"宿主说了什么 / 最后交付了什么"。工具过程不进 msgs——
    一次任务能跑几十条 ls/rg 输出，存进去下轮就要重读几万字，额度直接烧完。"""
    q = str(task or "").strip()
    if q:
        s.setdefault("msgs", []).append({"role": "user", "content": A.tag_user(q)[0]})
    s.setdefault("msgs", []).append({"role": "assistant", "content": str(out or "")[:2000]})
    s["msgs"] = s["msgs"][-40:]


def load(sid):
    if sid in SESSIONS:
        return SESSIONS[sid]
    try:
        with open(sess_path(sid)) as f:
            SESSIONS[sid] = json.load(f)
            return SESSIONS[sid]
    except Exception:
        return None



PWA_MANIFEST = {
    "name": "AJ", "short_name": "AJ", "start_url": "/", "scope": "/",
    "display": "standalone", "orientation": "portrait",
    "background_color": "#111418", "theme_color": "#111418",
    "icons": [
        {"src": "/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
        {"src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
        {"src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"},
    ],
}

# 2026-10-23：PWA。只缓存"壳"，/poll /mem /send 这些接口一律不缓存，
# 否则聊天记录会被旧响应顶掉，且 busy 状态可能卡死。
PWA_SW = r"""const C='aj-shell-v1';
const SHELL=['/','/manifest.json','/icon-192.png','/icon-512.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(C).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==C).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const r=e.request,u=new URL(r.url);
  if(r.method!=='GET'||u.origin!==location.origin){return}
  if(!SHELL.includes(u.pathname)){return}          // 接口/会话数据：绝不缓存
  e.respondWith(caches.match(r).then(c=>c||fetch(r).then(res=>{
    if(res&&res.status===200){const cl=res.clone();caches.open(C).then(x=>x.put(r,cl))}
    return res;
  }).catch(()=>caches.match('/'))));
});
"""

ICONS = {
    "/icon-192.png": base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAYAAABS3GwHAAADi0lEQVR42u3bQW4qMRBF0V4JW2TvbADEnAYBasvte5DeNMl/Vce29JNtm+hzud7u0shW/Bi8pIAYpCRBGJgkIRiQJCEYiGQhGIIkESheshCULVkESpYsAuVKFoFSJYtAmZJFoETJIlCepBEoTrIAlCZZBMqSNAJFSRaAkiSNQEGSBaAcSSNQjGQBKEXSCBQiAIgAIBIDoAxJI1CEACACgAgAIgDIsXn10QsAuaXf++gLgOzyQzAQgBLmXH4IAMgvPwQA5JcfAgAAAACA8vJDAAAAAAAAgF4BiC4/BAAAAAAAAOgXgOjyQwAAAAAAUF5+CAAAAAAAANA3ANHlhwAAAAAAAAC9AxBdfggAAAAAAADQPwDR5YcAAAAAAKC8/BAAAAAAAABgHgBElx8CAAAAAAAAzAWA6PJDAAAAAABQXn4IAAAAAAAAMCcAossPAQAAAAAAAOYFQHT5IQDgNACO/toCAAACwKzPnxFfXwCY9vQf+T0EgOlO/9HfRwAAQACY6fnjGQRA+vR3CwCQP/3dAgAAAAAA1eePZxAA+dPfLQBA+vR3CwAAAAAAlJ8/nkEApE9/twAAAAAAQPn54xkEgD979OeSAAAAAAAAAACA5YcAAAAAAMDyQwAAAAAAAAAAAFh+CAAAAAAAAAAAAMsPAQAAAACA5YcAAAAAAGC937UHAIAl/uqr+vMDAAAAAHj+eAYB4PR3CwDg5PRvAcDSAACAZ4NnEABOTLcAAE5LtwAAFgUAADwVPIMAcEq6BQBwQroFALAcAADgeeAZBICT0S0AgFPRLQCAhQAAAE8CzyAA8qehWwAAAAAAoLwEAMQB1E9BtwAA+eHrAAAAAOgBcP3rAQBD10URgFNPHwAYuD4AMGydxAA47fQCgCHrBgBD1k0MgGtePwAYro6KAJxuegLAYPUEgKHqKgbAqaYvAAxUXwAYps5iAJxmegPAEHUHgCHqDgAAdAeA5dcfAADoDwAA9AeA5deh/wgDQH8AWH4d7gF4fuoDBCD826BnB/DvEMvLr79FAPw6RMuvv2UAfDNEC6/DJQG8G6Tl1mEKgAgAIgCIfAAAgaSXHwABQCECgEgUAASSXn4AJA8AAkkvPwCSBwCBpJcfAMkDgEDSyw+B5JcfAMkDgEDSyw+B5JcfAskvPwSSX34IJL/8EEh++SGQ/PKDIPnFh0AsPwhi8UEQiw+CWHwg5GwL/wBJz0HH/q3rLwAAAABJRU5ErkJggg=="),
    "/icon-512.png": base64.b64decode("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"),
}

HTTP_DIR = HERE
_HTML_PATH = os.path.join(HTTP_DIR, "index.html")


_MIME = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
         ".gif": "image/gif", ".webp": "image/webp", ".bmp": "image/bmp",
         ".heic": "image/heic", ".pdf": "application/pdf", ".txt": "text/plain; charset=utf-8",
         ".mp4": "video/mp4", ".mp3": "audio/mpeg", ".m4a": "audio/mp4"}


def _mime(name):
    return _MIME.get(os.path.splitext(name)[1].lower(), "application/octet-stream")


def _load_html():
    """页面本体放在同目录 index.html（assets 里就是这么并排的）。
    读不到就给一个能自查的最小页，别静默 500。"""
    try:
        with io.open(_HTML_PATH, "r", encoding="utf-8") as f:
            return f.read()
    except Exception as e:
        return ("<!doctype html><meta charset=utf-8><title>AJ</title>"
                "<body style=\"font:15px/1.6 sans-serif;background:#111418;color:#e6e6e6;padding:24px\">"
                "<h2>页面没找到</h2><p>读不到 " + _HTML_PATH + "：" + str(e) + "</p></body>")


HTML = _load_html()

def ev(sid, typ, **kw):
    s = SESSIONS.get(sid)
    if not s:
        return
    s.setdefault("events", []).append(dict(i=len(s.get("events", [])), type=typ, **kw))
    save(sid)


def _kept_entities(task, said_batch, events):
    """本轮宿主说过的每一句，挑出里面"被明确要求处理"的实体，看有没有真被碰过。

    做法很土但有效：对每句话抽名词性短语（2~4 字中文块），去掉"已经/"收到"这类语气词；
    再到本轮的事件标题/正文里找有没有出现过。一句都没被碰过 → 判定这句话被吞了。
    返回第一句被吞的原话（没有就返回空串）。
    """
    blob = " ".join(
        (str(e.get("title") or "") + " " + str(e.get("body") or "") + " " + str(e.get("text") or ""))
        for e in (events or [])
    )
    for say in said_batch or []:
        t = (say or "").strip()
        if len(t) < 4 or A._is_control(t):
            continue
        # 抽 2~4 字的连续中文块，去掉纯语气/时间词
        chunks = re.findall(r"[\u4e00-\u9fa5]{2,4}", t)
        stop = ("收到", "已经", "一下", "帮我", "这个", "那个", "然后", "现在", "可以",
                "什么", "怎么", "我要", "你把", "给我", "接着", "继续")
        keys = [c for c in chunks if c not in stop][:5]
        if not keys:
            continue
        if not any(k in blob for k in keys):
            return t
    return ""


def worker(sid, task, files=None):
    s = SESSIONS[sid]
    # 2026-10-21 23:4x 修的坑：pending_answer 是上次 /answer 留下的，
    # 不清掉的话下一次 ask 会直接读到昨天那句"同意"，看起来像它在自问自答。
    s["pending_answer"] = None
    s["queue"] = []
    try:
        if not A.gate_ok():
            ev(sid, "err", text="急停开关开着（%s 存在），停。删掉它再继续。" % A.GATE)
            return
        _sk = A.skill_hint(task)
        sk = _sk[0] if isinstance(_sk, tuple) else _sk
        sk = [str(str(x).split("/")[0]) for x in (sk or [])]
        head = A.SYSTEM + A.skills_manifest(task) + A.skill_inject(task) + A.note_recall() + (A.mem_recall(task) or "")
        # 2026-10-21 宿主定案：「忘了可以搜索，然后再回答」。
        # 不塞历史（每任务干净开局），但先按本题关键词在档案里预检一遍，命中行直接摆在眼前，
        # 要不要读原文由它自己 <sb> 决定。
        try:
            _ms = A.mem_search(task)
        except Exception:
            _ms = ""
        if not A._is_control(task):
            head += (_ms or "")
        if sk:
            head += "\n【这次可能用得上的技能：" + "、".join(sk) + "】"
        # 2026-10-21 宿主定案：每任务干净开局。不再把会话里旧任务的 30 条问答灌进来
        # （旧逻辑会让"新会话"里出现几天前的对话，模型每轮重读一遍，还容易被旧结论带着跑）。
        # 会话文件照存不删，要查旧事走 /sdcard/记忆录/ 或 sessions.jsonl。
        # 2026-10-22 宿主定案（改掉 10-21 那版"每任务干净开局"）：
        # 同一会话内必须记得刚才说的话——旧版每轮只给模型 [系统, 本轮任务] 两条，
        # 于是"上一秒说的话下一秒又问一遍"。页面上那些对话是回放给宿主看的，
        # 模型根本看不见（server.py 的 hist 只走前端）。
        # 现在：本会话此前的问答一起带上，别的会话/历史档案一律不带（刷新=新会话，msgs 空）。
        # 窗口：最近 12 条、单条 800 字、总 6000 字封顶，工具过程不进（只存宿主话+最终交付）。
        _hist = []
        _bud = 6000
        for m in reversed(s.get("msgs", [])):
            if len(_hist) >= 12 or _bud <= 0:
                break
            c = str(m.get("content") or "").strip()
            if not c or m.get("role") not in ("user", "assistant"):
                continue
            c = c[:800]
            _bud -= len(c)
            _hist.append({"role": m["role"], "content": c})
        _hist.reverse()
        msgs = [{"role": "system", "content": head}] + _hist + \
               [{"role": "user", "content": A.tag_user(task)[0]}]
        if sk:
            # 2026-10-23：清单里的标注模型会忽略，把技能指令贴到本轮任务的紧前面
            msgs[-1]["content"] += ("\n\n【技能提示】这次任务对得上技能 " + "、".join(sk) +
                                    "。动手前先 <skill>" + sk[0] + "</skill> 读说明书，按书里写的步骤做；"
                                    "读完发现不适用再自己干。")
        if files:
            alist = "\n".join("  " + p for p in files)
            tip = ("\n\n【宿主上传了文件，路径就在这台机器上（不用换前缀）：\n" + alist +
                   "\n  先 <sh>ls -l <路径></sh> 确认在不在，再用 file / python3 判断类型；"
                   "图片没有本地识图能力，要看内容得让宿主在 App 里配视觉通道。】")
            msgs[-1]["content"] += tip
        acted = False        # 本次任务里执行过动作块没有
        none_streak = 0      # 连续几轮没给动作块
        # 2026-10-21 23:5x：宿主截图里那一幕——先问一句，再插一句，
        # 模型回「收到你的插话，已并入下一步」就收尾，**第一句的诉求被吞了**，
        # 页面上只剩"收到任务 / 收到插话"两张灰卡，看着像卡死。
        # 这里记下本轮宿主说过的每一句，收尾时对账：没办到的捡回来再跑一轮。
        said_batch = [task]
        _rescued = 0
        for rnd in range(1, A.MAX_ROUNDS + 1):
            if not A.gate_ok():
                ev(sid, "err", text="闸门关了，停。")
                return
            try:
                out = A.call_model(msgs)
            except Exception as e:
                ev(sid, "err", text=f"模型失败: {e}")
                return
            kind, val = A.parse(out)
            if kind not in ("none", "done", "stuck"):
                # 真执行过动作了：之后它说的纯文本就是在交结果，不要再打回逼它凑命令。
                # 2026-10-21 修：旧逻辑把"执行完给的结论"当没说完，模型于是无限重跑同一条命令
                # （实测 [2] none … [38] none 一路跑满 200 轮）。
                acted = True
                none_streak = 0
            if kind == "done":
                # 2026-10-21 23:5x：收尾前对账。宿主这一轮说了好几句，模型只回了"收到"就 done，
                # 那第一句诉求就白说了（页面上只剩两张灰卡，看着像卡死）。最多捡两回，别死循环。
                if _rescued < 2 and (rnd < A.MAX_ROUNDS):
                    _lost = _kept_entities(task, said_batch, s.get("events"))
                    if _lost:
                        _rescued += 1
                        ev(sid, "tool", title="（刚才那句没办到，捡回来）", body=_lost[:300])
                        msgs.append({"role": "assistant", "content": out})
                        msgs.append({"role": "user", "content":
                            "结果:\n你准备好了要收尾，但宿主这句还没被处理：\n· " + _lost +
                            "\n别只回'收到'——现在把它办完（该查查、该做做），再给 <done>。"})
                        continue
                ev(sid, "done", text=val)
                try:
                    A.daily_add("web-答", val)
                except Exception:
                    pass
                msgs.append({"role": "assistant", "content": out})
                _save_turn(s, task, out)
                save(sid)
                return
            if kind == "stuck":
                # 2026-10-21 23:5x：模型对"你是谁/能干什么"这种自我介绍题没法给动作块，
                # 于是吐 <stuck>，页面就一条红字"卡住"——看着像它不会聊天。
                # 它说的其实是想表达的答案，只是塞错了标签。有内容就当交付，空的才算卡住。
                _v = (val or "").strip()
                if len(_v) >= 6 and not acted:
                    ev(sid, "done", text=_v)
                    try:
                        A.daily_add("web-答", _v)
                    except Exception:
                        pass
                    msgs.append({"role": "assistant", "content": out})
                    _save_turn(s, task, out)
                    save(sid)
                    return
                ev(sid, "err", text="卡住: " + _v)
                return
            if kind == "ask":
                ev(sid, "ask", text=val)
                # 问题不阻塞宿主继续说话：面板不锁发送键，超时也只短暂等
                PENDING[sid] = threading.Event()
                got = PENDING[sid].wait(180)
                ans = s.get("pending_answer", "")
                ev(sid, "tool", title="宿主回答", body=ans or "(超时未答)")
                res = f"宿主回答：{ans}" if got else "宿主没回话（等超时了），请换个不依赖他的做法，或者停下来"
            elif kind == "sb":
                ev(sid, "tool", title="本机 $ " + val[:160], body="执行中…")
                s.setdefault("_cmds", []).append("sb: " + val[:200])
                res = A.sb(val)
                ev(sid, "tool", title="本机结果", body=(res or "")[:4000])
            elif kind == "img":
                # 2026-10-22：worker 原来漏了 img 分支——模型回 <img> 动作块被当 done 吞掉，
                # 网页端出图永远不出图。现在接上出图通道（文生图组，和对话路由分开）。
                _pr, _, _od = val.partition("|")
                ev(sid, "tool", title="出图 " + _pr.strip()[:120], body="生成中…")
                try:
                    res = A.img_gen(_pr, _od) if _od.strip() else A.img_route(_pr.strip())
                except Exception as e:
                    res = f"⛔ 出图异常: {e}"
                ev(sid, "tool", title="出图结果", body=(res or "")[:2000])
            elif kind == "sh":
                ev(sid, "tool", title="本地 $ " + val[:160], body="执行中…")
                s.setdefault("_cmds", []).append("sh: " + val[:200])
                res = A.run(val)
                ev(sid, "tool", title="本地结果", body=(res or "")[:4000])
            elif kind == "read":
                res = A.read_file(val)
                ev(sid, "tool", title="读 " + val, body=(res or "")[:4000])
            elif kind == "skill":
                res = A.skill_read(val)
                ev(sid, "tool", title="技能 " + val, body=(res or "")[:3000])
            elif kind == "write":
                p, c = val
                s["pending_write"] = {"path": p, "content": c}
                ev(sid, "ask", text=f"要写 {p}（{len(c)} 字节），同意吗？")
                PENDING[sid] = threading.Event()
                got = PENDING[sid].wait(600)
                ans = s.get("pending_answer", "")
                ok = got and "同意" in ans
                res = A.write_file(p, c, ok)
                ev(sid, "tool", title=f"写 {p} → {'已写' if ok else '被拒'}", body=(res or "")[:1000])
            elif kind == "mem":
                k, t = val
                res = A.mem_add(k, t)
            elif kind == "plan":
                ev(sid, "assistant", text="计划：\n" + val)
                if s.get("_plan_done"):
                    res = "计划已经写过了，再写一遍不算干活。立刻输出一个动作块。"
                else:
                    s["_plan_done"] = True
                    res = "(计划已记录，现在动手)"
            else:
                _t = (out or "").strip()
                if not _t:
                    ev(sid, "done", text="（模型给了空回复，先停在这儿）")
                    return
                none_streak += 1
                if acted:
                    # 动作执行完再给的纯文本 = 交结果，直接交付，绝不打回
                    ev(sid, "done", text=_t)
                    try:
                        A.daily_add("web-答", _t)
                    except Exception:
                        pass
                    msgs.append({"role": "assistant", "content": out})
                    _save_turn(s, task, out)
                    save(sid)
                    return
                if none_streak >= 2 and len(_t) >= 8:
                    # 连续两轮都在说话、且还没动过手：它就是在回答，不是没干完 → 交付
                    ev(sid, "done", text=_t)
                    try:
                        A.daily_add("web-答", _t)
                    except Exception:
                        pass
                    msgs.append({"role": "assistant", "content": out})
                    _save_turn(s, task, out)
                    save(sid)
                    return
                res = ("你只写了话没给动作块。如果你刚才那句话就是最终答复，**原样重复一遍**包成 "
                       "<done>那句话</done>，别换说法、别概括；如果活还没干完，立刻给一个动作块继续。")
            if kind not in ("sb", "sh", "ask", "write"):
                ev(sid, "tool", title=f"[{rnd}] {kind}", body=(res or "")[:1500])
            msgs.append({"role": "assistant", "content": out})
            msgs.append({"role": "user", "content": f"结果:\n{(res or '')[:6000]}"})
            # 宿主在跑的过程中说的话：插进上下文，别让他干等
            q = s.get("queue") or []
            if q:
                s["queue"] = []
                said_batch.extend(q)
                said = "\n".join("· " + x for x in q)
                msgs.append({"role": "user", "content":
                    f"【宿主插话（他不是在等结果，他改主意了或者有新要求）】\n{said}\n"
                    "**先看他的话要不要紧：**要紧就按他说的改（包括他新给的要求），"
                    "不要紧就接着干完原任务再回。**别只回一句'收到'就收尾——"
                    "那样宿主的第一句话就白说了。**"})
                ev(sid, "tool", title="（收到插话，已并入下一步）", body=said[:600])
            if len(msgs) > 60:
                msgs = [msgs[0]] + A.compress(msgs[1:]) if hasattr(A, "compress") else msgs[:1] + msgs[-36:]
        ev(sid, "err", text=f"到 {A.MAX_ROUNDS} 轮，已停。")
    except Exception:
        print(f"[worker 异常] sid={sid} task={task[:80]!r}", file=sys.stderr)
        print(traceback.format_exc(), file=sys.stderr)
        ev(sid, "err", text="干到一半出错了，traceback 已写进日志，这里先停下来。")
    finally:
        try:
            A.note_write(task, s.get("_cmds"))
        except Exception:
            pass
        s["busy"] = False
        save(sid)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if not _token_ok(self):
            return self._json({"error": "\u53e3\u4ee4\u4e0d\u5bf9"}, 401)
        # 2026-10-21 23:4x：宿主页面卡在"收到你的插话，已并入下一步"不动。
        # web.log 里看到 /poll 抛异常 → 前端只拿到 500 → busy 永远不退、模型名停在旧值。
        # 这里统一兜住 GET 的异常：宁可返回空数据，也别让整个页面失去响应。
        try:
            return self._get()
        except BrokenPipeError:
            return
        except Exception as e:
            print(f"[GET 异常] {self.path} → {type(e).__name__}: {e}")
            try:
                if self.path.startswith("/poll"):
                    self._json({"events": [], "busy": False,
                                "error": "poll 出错：" + str(e)}, 200)
                else:
                    self._json({"error": str(e)}, 500)
            except Exception:
                pass

    def _get(self):
        if self.path.split("?")[0] == "/" or self.path.startswith("/index"):
            b = HTML.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            if _q_token(self):
                # 用 ?k= 打开一次就把口令种进 Cookie，之后的 fetch 不用再带
                self.send_header("Set-Cookie", "ajtoken=" + TOKEN + "; Path=/; SameSite=Lax; HttpOnly")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        if self.path.split("?")[0].startswith("/uploads/"):
            # 上传的原图/附件：网页里直接 <img src="/uploads/x.png">
            from urllib.parse import unquote
            name = unquote(self.path.split("?")[0][len("/uploads/"):])
            p = os.path.join(UP_D, os.path.basename(name))
            if not os.path.isfile(p):
                return self._json({"error": "\u6ca1\u8fd9\u4e2a\u6587\u4ef6"}, 404)
            try:
                with open(p, "rb") as f:
                    b = f.read()
            except Exception as e:
                return self._json({"error": str(e)}, 500)
            self.send_response(200)
            self.send_header("Content-Type", _mime(name))
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        if self.path.split("?")[0] == "/manifest.json":
            b = json.dumps(PWA_MANIFEST, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/manifest+json; charset=utf-8")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        if self.path.split("?")[0] == "/sw.js":
            b = PWA_SW.encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/javascript; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        if self.path.split("?")[0] in ICONS:
            b = ICONS[self.path.split("?")[0]]
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Cache-Control", "public, max-age=86400")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        if self.path.startswith("/mem/list"):
            files = []
            try:
                for root, dirs, fns in os.walk(A.MEMD):
                    rel = os.path.relpath(root, A.MEMD)
                    for fn in fns:
                        p = os.path.join(root, fn)
                        try:
                            st = os.stat(p)
                        except Exception:
                            continue
                        nm = fn if rel == "." else os.path.join(rel, fn)
                        files.append({
                            "name": nm,
                            "size": st.st_size,
                            "mtime": time.strftime("%m-%d %H:%M", time.localtime(st.st_mtime)),
                        })
            except Exception:
                pass
            files.sort(key=lambda f: f["mtime"], reverse=True)
            self._json({"dir": A.MEMD, "files": files})
            return
        if self.path.startswith("/mem/read"):
            from urllib.parse import urlparse, parse_qs
            q = parse_qs(urlparse(self.path).query)
            name = (q.get("name") or [""])[0]
            p = os.path.join(A.MEMD, name)
            if ".." in name or name.startswith("/") or not os.path.isfile(p):
                self._json({"error": "没这个文件"}, 404)
                return
            try:
                with open(p, "r", errors="replace") as f:
                    self._json({"name": name, "content": f.read()[:80000]})
            except Exception as e:
                self._json({"error": str(e)}, 500)
            return
        if self.path == "/model/status":
            try:
                m = A.model_status() or {}
                m["instance"] = INSTANCE_ID
                self._json(m)
            except Exception as e:
                self._json({"error": str(e)}, 500)
            return
        if self.path == "/model/list":
            try:
                st = A.model_status()
                self._json({"pin": st["pin"], "current": st["current"],
                            "options": A.model_options()})
            except Exception as e:
                self._json({"error": str(e)}, 500)
            return
        if self.path.startswith("/poll"):
            from urllib.parse import urlparse, parse_qs
            q = parse_qs(urlparse(self.path).query)
            sid = (q.get("sid") or [None])[0]
            since = int((q.get("since") or [0])[0])
            if not sid:
                sid = uuid.uuid4().hex[:12]
                SESSIONS[sid] = {"msgs": [], "events": [], "busy": False}
            s = load(sid) or SESSIONS.setdefault(sid, {"msgs": [], "events": [], "busy": False})
            SESSIONS[sid] = s
            evs = [e for e in s.get("events", []) if e["i"] >= since]
            hist = []
            if since == 0:
                # 页面重新进来（刷新/点桌面图标）：把之前的对话回放一遍，别像新会话
                for m in s.get("msgs", []):
                    if m.get("role") == "user" and str(m.get("content", "")).strip():
                        c = str(m["content"])
                        if c.startswith("任务：") or c.startswith("提问") or c.startswith("宿主"):
                            hist.append({"role": "u", "text": c[:300]})
                    elif m.get("role") == "assistant" and str(m.get("content", "")).strip():
                        hist.append({"role": "a", "text": str(m["content"])[:400]})
                hist = hist[-20:]
            try:
                _ms = A.model_status()
            except Exception:
                _ms = None
            self._json({"sid": sid, "events": evs[:200], "busy": s.get("busy", False),
                        "hist": hist, "model": _ms})
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if not _token_ok(self):
            return self._json({"error": "\u53e3\u4ee4\u4e0d\u5bf9"}, 401)
        if self.path.split("?")[0] == "/upload":
            try:
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n)
                ctype = self.headers.get("Content-Type", "")
                bnd = ctype.split("boundary=")[-1].strip().encode()
                out = {}
                fname, data = "", b""
                for part in raw.split(b"--" + bnd):
                    if b"Content-Disposition" not in part:
                        continue
                    head, _, rest = part.partition(b"\r\n\r\n")
                    data = rest.rstrip(b"\r\n-")
                    hs = head.decode("utf-8", "replace")
                    for line in hs.split("\r\n"):
                        if "filename=" not in line:
                            continue
                        seg = line.split("filename=", 1)[1]
                        fname = seg.split(";")[0].strip().strip('"')
                if not data:
                    self._json({"error": "空文件"}, 400)
                    return
                os.makedirs(UP_D, exist_ok=True)
                ext = os.path.splitext(fname)[1].lower() or ".jpg"
                safe = time.strftime("%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6] + ext
                p = os.path.join(UP_D, safe)
                with open(p, "wb") as f:
                    f.write(data)
                self._json({"ok": True, "name": fname, "path": p, "size": len(data),
                            "url": "/uploads/" + safe})
            except Exception as e:
                self._json({"error": str(e)}, 500)
            return
        n = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            body = {}
        if self.path == "/ask":
            sid = body.get("sid") or uuid.uuid4().hex[:12]
            if sid not in SESSIONS:
                load(sid)
            if sid not in SESSIONS:
                SESSIONS[sid] = {"msgs": [], "events": [], "busy": False}
            s = SESSIONS[sid]
            if s.get("busy"):
                self._json({"sid": sid, "error": "上一个任务还没跑完"})
                return
            s["busy"] = True
            s["_cur_task"] = body.get("task", "")
            s["_task_head"] = body.get("task", "")
            ev(sid, "tool", title="收到任务", body=body.get("task", ""))
            try:
                A.daily_add("web-问", body.get("task", ""))
            except Exception:
                pass
            threading.Thread(target=worker, args=(sid, body.get("task", ""), body.get("files") or []), daemon=True).start()
            self._json({"sid": sid, "ok": True})
            return
        if self.path == "/mem/delete":
            name = body.get("name", "")
            p = os.path.join(A.MEMD, name)
            if ".." in name or name.startswith("/") or not os.path.isfile(p):
                self._json({"error": "没这个文件"}, 404)
                return
            try:
                bak = p + ".deleted-" + time.strftime("%m%d%H%M%S")
                os.rename(p, bak)
                self._json({"ok": True, "moved_to": bak})
            except Exception as e:
                self._json({"error": str(e)}, 500)
            return
        if self.path == "/answer":
            sid = body.get("sid")
            s = SESSIONS.get(sid)
            if not s:
                self._json({"error": "no session"}, 404)
                return
            s["pending_answer"] = body.get("answer", "")
            e = PENDING.pop(sid, None)
            if e:
                e.set()
            self._json({"ok": True})
            return
        if self.path == "/model/pin":
            # 2026-10-21 宿主提：像 minis 一样能看见用的什么模型、也能手动切。
            # 钉住只是"优先试它"，挂了自动往后换——钉死一个挂掉的模型等于把整个站废了。
            m = str(body.get("model") or "").strip()[:120]
            A.HOST_PIN["model"] = m
            st = A.model_status()
            st["ok"] = True
            self._json(st)
            return
        if self.path == "/say":
            # 它在忙时，宿主的话进队列，不报错
            sid = body.get("sid")
            s = SESSIONS.get(sid)
            if not s:
                self._json({"error": "no session"}, 404)
                return
            txt = body.get("text", "")
            q = s.setdefault("queue", [])
            q.append(txt)
            # 2026-10-21 23:4x：插话后模型常把"先看他的话"当成已经办完就收尾，
            # 于是页面上只剩一句"收到你的插话"，宿主的第一句任务被丢掉了。
            # 记一下这条任务原始诉求，worker 收尾时如果没交付，就把它重新捡起来。
            s.setdefault("_task_head", "")
            if not s["_task_head"]:
                s["_task_head"] = s.get("_cur_task", "") or ""
            # 立刻打断它正在跑的命令（不需要等它那轮跑完）
            # 2026-10-22 定案：写 "1" 不写 txt。两端 agent._bang_check() 只看文件在不在
            # （存在就 remove + 置 stop），从不读内容；写 txt 白占体积，还可能把宿主原话
            # 落在磁盘里。墙内墙外统一成 "1"。
            try:
                os.makedirs(os.path.join(A.AGENTD, "jobs"), exist_ok=True)
                with open(os.path.join(A.AGENTD, "jobs", "bang"), "w") as f:
                    f.write("1")
            except Exception:
                pass
            ev(sid, "tool", title="（插话已送达，正在打断它手上的命令）", body=txt[:400])
            self._json({"ok": True, "queued": len(q)})
            return
        self.send_response(404)
        self.end_headers()


def recover_sessions():
    """进程启动时，把所有 busy=True 的会话松绑。
    启动那一刻不可能有活 worker，卡住的 busy 必然是上次进程崩溃遗留。"""
    n = 0
    try:
        for fn in os.listdir(SESS_DIR):
            if not fn.endswith(".json"):
                continue
            fp = os.path.join(SESS_DIR, fn)
            try:
                d = json.load(open(fp))
            except Exception:
                continue
            if d.get("busy"):
                d["busy"] = False
                d["pending_write"] = None
                d["pending_answer"] = None
                try:
                    json.dump(d, open(fp, "w"), ensure_ascii=False)
                    n += 1
                except Exception:
                    pass
    except Exception:
        pass
    if n:
        print(f"[recover] 松绑了 {n} 个卡死会话")


def ensure_broker():
    """broker 由 App 拉起。App 起它的时候没有 export .env，所以它可能一个 key 都没有。
    这里做一次自愈：读到的 AJ_KEY 在环境里、而 8788 上的 broker 报告没有 key → 换掉它。
    key 只在子进程环境里传递，不打印、不落盘、不进日志。"""
    import subprocess
    import urllib.request

    def health():
        try:
            with urllib.request.urlopen(A.BROKER + "/health", timeout=3) as r:
                return json.loads(r.read().decode("utf-8", "replace"))
        except Exception:
            return None

    h = health()
    if h and h.get("has_key"):
        return "ok"
    if not os.environ.get("AJ_KEY"):
        return "没 key（.env 里没有 AJ_KEY，或 App 没配服务商）"
    subprocess.run(["sh", "-c", "pkill -f '[b]roker.py' 2>/dev/null; true"], timeout=10)
    time.sleep(0.6)
    b = os.path.join(HERE, "broker.py")
    if not os.path.exists(b):
        return "缺 broker.py"
    lf = open(os.path.join(HERE, "broker.log"), "ab")
    try:
        subprocess.Popen([sys.executable, b], cwd=HERE, env=dict(os.environ),
                         stdin=subprocess.DEVNULL, stdout=lf, stderr=lf,
                         start_new_session=True)
    except Exception as e:
        return "broker 起不来：%s" % type(e).__name__
    for _ in range(20):
        time.sleep(0.4)
        h = health()
        if h and h.get("has_key"):
            return "已带 key 重启"
    return "broker 起来了但没读到 key"


if __name__ == "__main__":
    try:
        open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.pid"), "w").write(str(os.getpid()))
    except Exception:
        pass
    recover_sessions()
    print("AJ web  http://%s:%d/?k=%s" % (HOST, PORT, TOKEN), flush=True)
    print("家目录 %s ｜ broker %s ｜ 页面 %s" % (A.AGENTD, A.BROKER, _HTML_PATH), flush=True)
    print("broker: %s" % ensure_broker(), flush=True)
    srv = ThreadingHTTPServer((HOST, PORT), H)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
