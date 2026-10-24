#!/usr/bin/env python3
# agent.py —— 手机侧的"另一个我"：接管沙箱的全权智能体
# 用法: agent "任务"     |   agent -c 续接   |   agent -m 线 "任务"
#
# 2026-10-20 重写：SYSTEM 只给"流程说明书"（去哪查、按什么顺序、怎么验），
# 不喂记忆内容、不喂技能正文——那些它自己 rg。闸门每轮复查，改文件必须授权。
import json, os, re, subprocess, sys, time, shlex, shutil, threading
import urllib.request, urllib.error

# ══════════════════════════════════════════════════════════════
# 移植说明（App assets 版）
#   * 单机：没有 Termux、没有沙箱、没有 ssh —— 只有本机 Alpine
#   * 家目录：AJ_HOME（默认 /host/ajhome），记忆/技能/笔记/日志全在它下面
#   * 模型：只走本机 broker POST http://127.0.0.1:8788/chat
#           key 全归 broker（AJ_KEY/AJ_BASE/AJ_MODEL），本文件不出现任何 key
# ══════════════════════════════════════════════════════════════
AJ_HOME = os.path.abspath(os.environ.get("AJ_HOME", "/host/ajhome"))
HOME = AJ_HOME
AGENTD = os.environ.get("AJ_WALL_DIR") or AJ_HOME
JOBS = os.path.join(AGENTD, "jobs")
STATE = os.path.join(JOBS, "current.json")
BANG_FILE = os.path.join(JOBS, "bang")      # 存在=宿主插话，正在跑的命令要被打断
LOG = os.path.join(JOBS, "tasks.log")
SESSIONS = os.path.join(JOBS, "sessions.jsonl")
MEMD = os.path.join(AGENTD, "memory")       # 记忆库唯一位置
MEM_SHARED = MEMD
NOTE_D = os.path.join(AGENTD, "notes")
OUT_D = os.environ.get("AJ_OUT") or "/host/out"   # 跟父 AJ 的 filesDir/out 对齐，产物才回显
SKILL_ROOTS = [os.path.join(AGENTD, "skills")]
WALL_GATE_DIR = os.path.join(AGENTD, "gate")
GATE = os.path.join(WALL_GATE_DIR, "STOP")
IN_WALL = True          # 兼容旧分支：所有执行都在本机，"墙内/墙外"这件事已不存在

SHELL_BIN = os.environ.get("AJ_SHELL", "/bin/sh")
HAS_RG = shutil.which("rg") is not None    # Alpine 精简镜像不一定带 ripgrep，检索要能退到 grep
BROKER = os.environ.get("AJ_BROKER", "http://127.0.0.1:8788").rstrip("/")
BROKER_TIMEOUT = int(os.environ.get("AJ_BROKER_TIMEOUT", "300"))
TERMUX_SSH = []         # 已废弃：这台机器上没有 Termux（保留名字，旧调用点不会炸）

for _d in (AGENTD, JOBS, MEMD, NOTE_D, OUT_D, WALL_GATE_DIR, SKILL_ROOTS[0],
           os.path.join(MEMD, "daily")):
    try:
        os.makedirs(_d, exist_ok=True)
    except Exception:
        pass

MAX_ROUNDS = int(os.environ.get("AGENT_ROUNDS", "200"))
# 付费兜底位：免费候选全挂后才启用（宿主 2026-10-21 定案）。
# 可改环境变量 AGENT_PAID_FALLBACK 换掉；设成空串即关闭付费兜底。
PAID_FALLBACK = os.environ.get("AGENT_PAID_FALLBACK",
                               "9e8d2de1-80bf-4399-90c4-b0302ed9e2e9/deepseek-flash")

# 模型只有一个出口：本机 broker（见 call_model）。这里只留显示用的名字。
PAID_FALLBACK = ""
GOOD_MODELS = [x.strip() for x in os.environ.get("AJ_MODELS", "").split(";") if x.strip()]
CF_POOL = []

# 模型"能回话"不等于"会干活"。GLM-4-Flash 活着但常漏动作块、凭想象交付；
# 光看它没报错就判定路由可用，结果是 AJ 一直停在它上面空转。
# 这里记次数：同一任务里被本闸拦到第 N 次，就认为这个免费模型干不了活，
# 本任务剩余轮次直接切付费兜底。
_WEAK_LIMIT = int(os.environ.get("AGENT_WEAK_LIMIT", "2"))
_WEAK = {"hits": 0, "fired": False}
# 钉线：环境变量优先，其次读配置文件（重启/重新拉起后仍生效）
LINE_FILE = os.path.join(AGENTD, ".line")
def model_short(eid):
    """e81cbbbf-…/GLM-4-Flash → GLM-4-Flash。给界面看的短名。"""
    s = str(eid or "")
    return s.split("/", 1)[1] if "/" in s else s


def _last_model_of(name):
    """这个模型最近一次成功/失败的时间（失败台账口径，不含 TTL 过滤——过期也算"见过"）。"""
    try:
        d = json.load(open(FAIL_FILE))
    except Exception:
        return None
    rec = d.get("m:" + name) or {}
    return rec.get("t") if isinstance(rec, dict) else None


def model_options(pin=None, limit=40):
    """给网页端下拉框：路由大脑排出来的模型，前面挂上当前钉住的（如果有）。
    不调 probe、不发请求，纯读本地缓存和台账——界面要秒开。"""
    # 界面要秒开：链算一次缓存 120 秒。钉模型本身走 HOST_PIN，不受这个缓存影响。
    import time as _t
    _now = _t.time()
    if _OPT_CACHE.get("at", 0) + 120 < _now or not _OPT_CACHE.get("chain"):
        try:
            _OPT_CACHE["chain"] = _build_chain()
        except Exception:
            _OPT_CACHE["chain"] = _OPT_CACHE.get("chain") or []
        _OPT_CACHE["at"] = _now
    chain = _OPT_CACHE["chain"]
    opts = []
    seen = set()
    for i, eid in enumerate(chain[:limit]):
        if eid in seen:
            continue
        seen.add(eid)
        opts.append({
            "id": eid, "short": model_short(eid),
            "tier": "paid" if eid == PAID_FALLBACK else ("free" if i < 12 else "backup"),
            "last": _last_model_of(eid),
        })
    pin = pin if pin is not None else HOST_PIN.get("model", "")
    if pin and pin not in seen:
        opts.insert(0, {"id": pin, "short": model_short(pin), "tier": "pinned", "last": None})
    return opts


def model_status():
    """当前在用哪个模型、这个任务用过哪些。网页端 statbar + 模型表都用它。"""
    cur = HOST_PIN.get("model") or LAST_MODEL.get("final") or (LINE or "")
    return {
        "pin": HOST_PIN.get("model", ""),
        "auto": not HOST_PIN.get("model"),
        "current": cur,
        "current_short": model_short(cur),
        "used": [model_short(x) for x in LAST_MODEL.get("used", [])],
        "final": model_short(LAST_MODEL.get("final", "")),
        "line": model_short(LINE),
        "paid_fallback": model_short(PAID_FALLBACK),
    }


def _load_line():
    v = os.environ.get("AGENT_MINIS_MODEL", "")
    if v:
        return v
    try:
        for ln in open(LINE_FILE):
            ln = ln.strip()
            if ln and not ln.startswith("#"):
                return ln
        return ""
    except Exception:
        return ""
LINE = _load_line()
# 本任务实际用过哪些模型、最后用的哪个：给网页端"现在用的什么模型"用。
# 只保留最近 1 个任务，进程内即可，不必落盘。
LAST_MODEL = {"task": "", "used": [], "final": ""}
# 宿主在网页上手动钉的模型（host:port 的 /model 接口写）。只在本进程内生效，
# 重启后回到"自动"——手动切换本来就是临时意图，重启还钉着反而莫名其妙。
HOST_PIN = {"model": ""}
_OPT_CACHE = {"at": 0, "chain": []}   # model_options 的链缓存（界面秒开用）
HERE = os.path.dirname(os.path.abspath(__file__))
CONF_MODELS = os.path.join(HERE, "models.json")          # 父写：模型清单 + 排序
CONF_REPORT = os.path.join(HERE, "health-report.jsonl")  # 子写：本轮真实结果，回给父

# ---- 失败台账：不靠盲测，让失败自己暴露 ----
# model-brain 的设计是"用到才测"，但 probe 要逐个打 73 个模型，既慢又烧额度。
# 改法：模型真正调用失败时（限流/key 失效/不存在），当场记一笔到台账，
# 下次组候选链时直接跳过——零额外请求，且记录的是第一手结果。
# 按"provider/id" 记账，不按完整模型串，这样同 provider 的 key 失效能一次跳过整组。
FAIL_FILE = os.path.join(AGENTD, ".fail.json")
FAIL_TTL = int(os.environ.get("AGENT_FAIL_TTL", "7200"))   # 2 小时自动过期
_FAIL_RE = re.compile(r"rate.?limit|too many|429|invalid api key|unauthorized|"
                      r"401|authentication|not found|no such model|不存在|无权限", re.I)

def _fail_load():
    try:
        d = json.load(open(FAIL_FILE))
        return d if isinstance(d, dict) else {}
    except Exception:
        return {}

def _fail_note(model, err, provider=""):
    """记一笔失败。provider 为空时只记该模型。"""
    now = time.time()
    d = _fail_load()
    # 顺手清过期
    for k in [k for k, v in d.items() if now - v.get("ts", 0) > FAIL_TTL]:
        d.pop(k, None)
    for k in [f"m:{model}"] + ([f"p:{provider}"] if provider else []):
        d[k] = {"ts": now, "err": str(err)[:120]}
    try:
        with open(FAIL_FILE, "w") as f:
            json.dump(d, f)
    except Exception:
        pass

def _fail_clear(model):
    """这个模型这次成功了 → 它的失败记录作废（复活）。"""
    d = _fail_load()
    if d.pop(f"m:{model}", None) is not None:
        try:
            with open(FAIL_FILE, "w") as f:
                json.dump(d, f)
        except Exception:
            pass

def _fail_clear_provider(model):
    """组级失败由该组里任一模型成功而解除：说明 key 是好的，是别的模型自己的问题。"""
    prov = model.split("/", 1)[0] if "/" in model else ""
    if not prov:
        return
    d = _fail_load()
    if d.pop(f"p:{prov}", None) is not None:
        try:
            with open(FAIL_FILE, "w") as f:
                json.dump(d, f)
        except Exception:
            pass

def _fail_alive(model):
    """该模型是否还有效的失败记录。按 agent.py 里 provider/id 的写法拆。"""
    d = _fail_load()
    now = time.time()
    prov = model.split("/", 1)[0] if "/" in model else ""
    for k in (f"m:{model}", f"p:{prov}"):
        v = d.get(k)
        if isinstance(v, dict) and now - v.get("ts", 0) <= FAIL_TTL:
            return True
    return False

_CHITCHAT = re.compile(r"你是谁|你是|你叫什么|自我介绍|你好|您好|在吗|谢谢|辛苦|"
                       r"介绍.{0,4}你自己|^hi$|^hello$|^hey$", re.I)

SYSTEM = r"""你是 Aj，住在这台 Android 手机里的原生智能体（proot 里的 Alpine Linux），宿主用浏览器跟你说话。
宿主是你唯一的上级。他交代一件事，你想清楚怎么做，做完把结果给他。

## 你住在哪
- 只有一个执行面：本机的 Alpine shell。所有命令都在 <sh> 里跑，python3 / curl / apk 都在这。
- 你的家目录是 @AJHOME@：记忆、技能、笔记、日志都在这棵树下。
- 这台机器上没有第二台机器：没有 ssh、没有 Termux、没有 /sdcard、没有 /var/minis。别试着 ssh 或 cd 到别处。
- 网页壳 /host/web/server.py 和你（agent.py）是同一个程序的两半，不是别人。

## 动手前先查
- 问"是什么/在哪/怎么用"：先 which / ls / --help，再查记忆。
- 技能：上下文末尾有「技能清单」（从 @AJHOME@/skills 自动扫）。有对口的就 <skill>名字</skill> 读全文、照书里的步骤做；没有就自己干，别硬套。
- 记忆：<sh>rg -n "词" @AJHOME@/memory</sh>（镜像里若没有 rg，就用 grep -rn），查不到就直说不知道，不许编。
- 工具是 Alpine 精简版：sh / busybox（grep sed awk find）/ python3 / curl 一定有；rg、bash 这类不一定有，缺了就换等价命令，别反复重试。

## 硬规矩
- 改文件用 <write>，会弹授权卡，宿主同意才写；改前备份，改后拿证据（状态码/返回体/进程）。
- 探测上限按目标算：同一目标最多 3 次，换路径/参数都算同一次；第 3 次不行就 <stuck>。
- 涉及位置/端口/老约定，没 rg 过不许答。
- 报服务状态分三件：进程 / 端口 / 真能连上，没测就说没测。
- 宿主在派活、提问或批评。说他做错了就是批评——认、改、举证，别反问。
- 停手必须 <stuck>：试了什么 / 卡在哪 / 要宿主做什么。不许沉默换动作。

## 输出格式
一次一个动作，等结果回来再走下一步。正文少写，动作块写对。

### 命令
<sh>命令</sh>   本机 Alpine shell（sh -lc）执行，超时 300s，长任务挂后台写日志。
<sb>命令</sb>   同一个执行面的别名（老习惯），等价于 <sh>，一样在本机跑。
   失败先读错误输出；同一调用重复时系统会给分级提醒，15 次仍原样会被强制停下。

### 文件
<read>路径</read> 或 <read>路径 起-止</read>   带行号，默认 400 行；截断会给续读起点。别用 cat/head 拼。
<write path="完整路径">完整新内容</write>   整篇写，授权卡 + 自动备份。只改几行别整篇重写，用 sed/rg/python 精确替换。

### 技能与记忆
<skill>名字</skill>   读技能说明书。清单里标「对得上」的先读，按书里的步骤做。
<mem kind="fact">结论</mem>   记长期结论，只记查证过的，没查证会被拒收。

### 交付
<plan>计划</plan>   3 步以上多步任务，只写一次。
<ask>要宿主拍板的事</ask>   花钱、动他东西、方向二选一时用；能自己查的别问。
<done>一两句：做了什么、结果、剩什么</done>
<stuck>试了什么、卡在哪、要他做什么</stuck>

说 <done> 前自查：改了什么 / 生效了吗 / 弄坏什么没有 / 回滚得了吗 / 试过换路径没有。"""
SYSTEM = SYSTEM.replace("@AJHOME@", AGENTD)




# ---------------- 基础执行 ----------------
# 打断机制：宿主插话时置位，正在跑的命令立刻被杀
BANG = {"stop": False}

def _bang_check():
    """外部（网页壳）往 BANG 文件写内容就代表宿主插话了"""
    try:
        if os.path.exists(BANG_FILE):
            os.remove(BANG_FILE)
            BANG["stop"] = True
    except Exception:
        pass

_REPEAT = {"key": None, "n": 0, "fail": 0, "reminded": 0}


def repeat_guard(kind, cmd):
    """dsh 式重复检测：完全相同的调用连续计数，3/5/8 分级提醒，不硬拦。
    2026-10-23 照 dsh 的 repeat-tool-reminder 重写（原版「失败2次后第3次硬拦」会把模型逼死）。
    dsh 只提示不阻断，让模型自己换走法；连续 15 次仍原样才兜底强停。
    返回 (True, None) = 放行；(True, 提醒) = 放行并在结果后追加提醒；
    (False, 拦截) = 不执行，直接把拦截文本当结果。
    """
    key = (kind, (cmd or "").strip())
    if key != _REPEAT["key"]:
        _REPEAT["key"] = key
        _REPEAT["n"] = 0
        _REPEAT["fail"] = 0
        _REPEAT["reminded"] = 0
    _REPEAT["n"] += 1
    n = _REPEAT["n"]
    if n == 1:
        return (True, None)
    if n >= 15:
        print(f"  [闸] 同一调用第 {n} 次 → 强制停止")
        return (False, f"⛔ 同一调用已连续 {n} 次没有任何参数变化，强制停止。\n"
                       f"命令：{key[1][:200]}\n换做法，或用 <stuck> 说清卡在哪。")
    tool = {"sh": "本地命令 <sh>", "sb": "沙箱命令 <sb>", "read": "<read>"}.get(kind, kind)
    head = key[1][:160].replace("\n", " ")
    if n == 3:
        return (True, f"【提醒】你在用完全相同的参数重复调用 {tool}（第 {n} 次）。"
                      f"先仔细看上一次的结果：任务没完成就换一种做法或换参数，不要原样再来。")
    if n == 5:
        return (True, f"【重复调用提醒】\n- 工具：{tool}\n- 连续次数：{n}\n- 参数：{head}\n"
                      f"这些重复没有推进任务。不要再原样调用：换动作、换参数，或证据够了就收尾。")
    if n == 8:
        return (True, f"【重复调用警告】{tool} 已连续 {n} 次完全相同调用，继续重复不会改变结果。"
                      f"必须换方案；换不了就用 <stuck> 说清卡在哪。")
    return (True, None)


def _repeat_note(kind, cmd, res):
    """记一次失败。只认明确的失败信号，正常输出一律不算。
    2026-10-22 坑：原先把"输出不足 3 字符"也当失败，`echo hi` 这种正常短输出被误判，
    连续两次就把成功命令拦了。现在只认明确的错误特征串，短输出不算失败。
    """
    s = (res or "").strip()
    failed = False
    for sig in ("⛔", "No such file or directory", "Permission denied",
                "command not found", "超时", "(超时",
                "Traceback (most recent call last)",
                "Address already in use", "can't open", "cannot open", "not found"):
        if sig in s:
            failed = True
            break
    if failed:
        _REPEAT["fail"] += 1


def _exec(argv, timeout=300, stdin=None, want_rc=False):
    try:
        if stdin is not None:
            # 需要喂 stdin 的场景（把请求体送进沙箱）。短命令，直接 communicate。
            # 注意：text=True 时必须传 str，传 bytes 会卡死/报错。
            p = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, text=True, errors="replace")
            if isinstance(stdin, (bytes, bytearray)):
                stdin = stdin.decode("utf-8", "replace")
            out, _ = p.communicate(stdin, timeout=timeout)
            return (out or "").strip("\n")
        p = subprocess.Popen(argv, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True, errors="replace")
        # 2026-10-22 修管道竞态：旧写法"先等 poll() 结束，再 read()"，
        # 输出超过管道缓冲区（约 64KB）时子进程卡在 write 上永远退不出，
        # poll() 一直 False → 拖到 timeout 才返回，界面看起来就是"读了半天没结果"。
        # 现在边跑边在后台线程里把管道排空，再等进程收尾。
        _buf = []
        def _drain():
            try:
                for line in p.stdout:
                    _buf.append(line)
            except Exception:
                pass
        _th = threading.Thread(target=_drain, daemon=True)
        _th.start()
        t0 = time.time()
        while True:
            if p.poll() is not None:
                break
            _bang_check()
            if BANG["stop"]:
                try:
                    p.kill()
                except Exception:
                    pass
                BANG["stop"] = False
                return "(宿主插话了，这条命令被打断。先说他说的事。)"
            if time.time() - t0 > timeout:
                try:
                    p.kill()
                except Exception:
                    pass
                return f"(超时 {timeout}s)"
            time.sleep(0.2)
        _th.join(3)
        out = "".join(_buf)
        # 只剥行尾换行，不剥行尾空格——旧代码用 .strip() 把 "10\n" 剥成 "1"（末尾的 0 被当空白剥走），
        # wc -c 之类的数值输出被截断，写文件回校全部误判。2026-10-21 修。
        text = (out.strip("\n") or "(无输出)")[:8000]
        if want_rc:
            return (p.returncode, text)
        return text
    except Exception as e:
        return f"(执行异常: {e})"


def run(cmd, timeout=300):
    """在本机执行命令（这台机器就是唯一的机器，没有第二跳）。"""
    return _exec([SHELL_BIN, "-lc", cmd], timeout)


def termux_ssh(cmd, timeout=300):
    """【故意短路】旧路径：ssh -p 8022 u0_a459@127.0.0.1 回 Termux 执行。
    App 里没有 Termux、没有 8022 端口，直接返回明确错误，绝不真的去连。"""
    return ("⛔ 这台机器上没有 Termux（也没有 8022 的 ssh 通道），这条命令没有执行。"
            "用 <sh> 本地执行：" + str(cmd)[:300])


_SSH_CHK = {"ok": True, "msg": ""}


def _ssh_precheck():
    """旧版"进沙箱前自检 ssh"。App 里没有沙箱、没有 2222 通道，永远返回空串（=通过）。"""
    return ""


def sb(cmd, timeout=300):
    """执行命令。历史叫法"墙内执行"；本机就是唯一执行面，直接本地跑。"""
    return _exec([SHELL_BIN, "-lc", cmd], timeout)


def _gate_log(msg):
    try:
        os.makedirs(WALL_GATE_DIR, exist_ok=True)
        with open(os.path.join(WALL_GATE_DIR, "log"), "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')}  [墙] {msg}\n")
    except Exception:
        pass


def gate_ok():
    """墙内急停：STOP 文件存在即停。手机侧写不出来，dsh 也碰不到。"""
    return not os.path.exists(GATE)


def _approve_armed():
    """2026-10-21 起作废：宿主废除了文件式放行条。保留函数名防止调用点报错。"""
    return True


def gate_wait(rounds=20, gap=3):
    """闸门关了就在这儿等放行，最多等 rounds*gap 秒"""
    for i in range(rounds):
        if gate_ok():
            return True
        time.sleep(gap)
    return False


READ_LIMIT = 24000      # 单次返回给模型的字符上限（2026-10-22 由 12000 放宽）
READ_DEFAULT_LINES = 400


def read_file(p):
    """读文件。支持 "路径 起始行-结束行" 读片段；默认前 400 行，超长时明确告诉它怎么往下读。

    2026-10-22 修（宿主点到的问题）：旧版 head 出来的内容没有行号，模型看到"截断"后
    只能一行行往后猜位置、反复试，server.py 那种文件读了 10 遍还没读完。
    现在：① 每行带行号（cat -n 风格），续读直接抄提示里的数字；
          ② 按行号切片而不是 head 猜；
          ③ 截断时明确告诉它"本次实际返回到第 N 行"，续读起点是 N+1，不会重叠也不会跳。
    """
    spec = (p or "").strip().strip('"').strip("'")
    m = re.match(r"^(.+?)\s+(\d+)\s*[-~]\s*(\d+)$", spec)
    if m:
        path = m.group(1).strip()
        start, end = int(m.group(2)), int(m.group(3))
    else:
        path, start, end = spec, 1, READ_DEFAULT_LINES
    # 2026-10-22 修：read_file 按"路径是否 /var/ 开头"选执行体，/tmp、$HOME 这类本地路径
    # 会被送到单向门 ssh（Termux）去读，读回来只有一行 ssh 警告 → 核对必然失败。
    # 墙内一律读沙箱自己；写完也要在本地（沙箱）读回自己写的那份。
    r = sb if (IN_WALL or path.startswith("/var/")) else run
    total = 0
    try:
        total = int((r(f"wc -l < {path} 2>/dev/null") or "").strip().split()[0])
    except Exception:
        total = 0
    if end < start:
        start, end = end, start
    # 带行号取片段。大区间先按字节多拉一段，避免 ssh 把 awk 的输出截在管道里
    # （2026-10-22：写入核对用 1-200000 读回，只拿到 1 行，核对必然失败）。
    cmd = (f"awk 'NR>={start} && NR<={end} "
           f"{{printf \"%6d| %s\\n\", NR, $0}}' {path} 2>/dev/null")
    txt = r(cmd) or ""
    if not txt.strip():
        if total and start > total:
            return f"(文件只有 {total} 行，{start} 行之后没有了)"
        return "(空或读不到)"
    if end - start > 600:
        # 大区间：按字节补拉，最多拉到 READ_LIMIT*3（够判断截断位置，也不再往上堆）
        need = READ_LIMIT * 3
        big = r(f"{cmd} | head -c {need}") or ""
        if len(big.encode("utf-8")) > len(txt.encode("utf-8")):
            txt = big
    # 续读起点：用本次请求的起始行 + awk 真实吐出的行数，不用字符数折算。
    # 2026-10-22 坑：trim 过的输出末行没换行，txt.count("\n") 少算一行 → 倒数第二页被当成末页、少给一行。
    got = txt.count("\n") + 1
    last_line = min(end, start + got - 1)
    src_line = txt.split("\n")[-1] if txt else ""
    hm = re.match(r"^\s*(\d+)\|", src_line)
    if hm:
        n = int(hm.group(1))
        # 被字符上限切过时，末行是半截的，只认完整行
        if "\n" in txt:
            last_line = min(last_line, n) if n <= last_line else last_line
    body = txt[:READ_LIMIT]
    if len(txt) > READ_LIMIT:
        # 只在真的被字符上限切过时才回退到上一个换行；
        # 否则短文件会被退到只剩第一行（2026-10-22 实坑：2 行文件只回 1 行）
        cut = body.rfind("\n")
        if cut > 0:
            body = body[:cut]
    if len(txt) > READ_LIMIT and body:
        # 被截了：把末行行号重新对齐到 body 里真实存在的那一行
        for ln in reversed(body.splitlines()):
            hm2 = re.match(r"^\s*(\d+)\|", ln)
            if hm2:
                last_line = int(hm2.group(1))
                break
    notes = []
    # 用实际吐出的字节数判断是否被截，别用 len(txt) > len(body)（永远为真）
    truncated = len(txt) > READ_LIMIT
    if truncated:
        notes.append(f"本次按字符上限截到第 {last_line} 行")
    if total > last_line:
        notes.append(f"全文共 {total} 行")
    if notes:
        body += f"\n\n【截断提示】{'，'.join(notes)}。"
        if last_line < total:
            body += f"续读用：<read>{path} {last_line + 1}-{last_line + 300}</read>"
    return body


def write_file(p, content, approved):
    """approved=False 时只出授权卡，不落盘"""
    bak = f"{p}.bak-{time.strftime('%m%d%H%M%S')}"
    if not approved:
        return (f"【授权提示卡】已拦下写入，未落盘。\n"
                f"文件：{p}\n"
                f"备份将存：{bak}\n"
                f"内容长度：{len(content)} 字节\n"
                f"前 600 字：\n{content[:600]}\n"
                f"→ 放行条机制已于 2026-10-21 作废（宿主：文件式授权挡不住同 uid 的 dsh）。如需全停，touch {GATE}。")
    # ---- 写前三道闸：整篇覆盖会毁掉文件 ----
    old = read_file(p)
    old_len = len(old) if old and old != "(空)" else 0
    new_len = len(content)
    if old_len > 200 and new_len < old_len * 0.5:
        return (f"⛔ 拒写：原文 {old_len} 字节，你只给 {new_len} 字节，"
                f"这是整篇覆盖会毁掉原文件。\n"
                f"改行请用 <sh> 做精确替换（sed/awk/python），别整篇重写。")
    # heredoc 要求结束符独占一行：若 content 已以换行结尾，再加 \n 就会多写一个空行
    # （实测 'print(1)\n' 写成了 'print(1)\n\n'）。所以按需补换行。2026-10-21 修。
    pad = "" if content.endswith("\n") else "\n"
    body = (f"cp {p} {bak} 2>/dev/null; "
            f"cat > {p} <<'AGENTEOF'\n{content}{pad}AGENTEOF\n"
            f"echo written $(wc -c < {p})")
    # 2026-10-22 修：墙内（IN_WALL=True）用 sb() 执行，但旧代码按"路径前缀"决定走 run/sb，
    # 于是 /tmp/xxx 这种非 /var/ 路径会被送去 run() = 单向门 ssh 到 Termux，
    # 墙外的 /tmp 跟沙箱的 /tmp 不是一个地方 → 写入实际没落盘、回校取回 -1。
    # 统一按 IN_WALL 选执行体，别再按路径猜。
    if IN_WALL:
        res = sb(body)
        got = sb(f"wc -c < {p} 2>/dev/null")
        rb = sb
    else:
        res = run(body)
        got = run(f"wc -c < {p} 2>/dev/null")
        rb = run
    # 写完回校：长度对不上 → 自动回滚
    # wc -c 数的是字节；len() 数的是字符。中文一字 3 字节，用 len() 比永远不等 →
    # 所有含中文的写入都会被判"长度不符"并自动回滚（2026-10-22 修，boundary.md 1805≠2244 就是这）。
    expect = len(content.encode("utf-8"))

    if re.search(r"^AGENTEOF$", content, re.M):
        return "⛔ 拒写：内容里含 AGENTEOF 行，会截断 heredoc。换个写法（用 python 写文件）。"
    try:
        real = int((got or "0").split()[0])
    except Exception:
        real = -1
    if real != expect:
        rb(f"cp {bak} {p} 2>/dev/null")
        return f"⛔ 写完长度不符（期望 {expect}，实际 {real}），已自动回滚到 {bak}"
    # 2026-10-22 加：字数对得上不代表内容对得上，再核一次实际落盘的内容。
    # 目的不是防手滑，是防"嘴上说改完了"——写入没生效时必须当场报错，不许当成功返回。
    rbback = read_file(f"{p} 1-{max(1, content.count(chr(10)) + 2000)}")
    core = re.sub(r"^\s*\d+\| ?", "", rbback, flags=re.M)          # 去掉行号前缀
    core = core.split("\n\n【截断提示】")[0]
    if content.strip() and content.strip() not in core:
        rb(f"cp {bak} {p} 2>/dev/null")
        return (f"⛔ 内容核对失败：写入 {expect} 字节但回读对不上，已回滚到 {bak}。"
                f"（别声称改完了，先查为什么没落盘）")
    return f"{res} | 已核对：{expect} 字节，内容与写入一致"


# ---------------- 技能库 ----------------
def skill_list():
    return sb("ls -1 %s 2>/dev/null | tr '\\n' ' '" % shlex.quote(SKILL_ROOTS[0]))


def _ms_token():
    """本机没有出图钥匙。保留函数给旧调用点。"""
    return "", ""


def img_gen(prompt, out=None):
    """出图：交给父 AJ 的 broker（key 只在那边）。图存到 OUT_D，返回路径。"""
    import base64, json as _j, os as _os, time as _t
    prompt = (prompt or "").strip()
    if not prompt:
        return "⛔ 没给画什么。"
    broker = _os.environ.get("AJ_BROKER") or "http://127.0.0.1:8788"
    body = _j.dumps({"prompt": prompt}).encode()
    rq = urllib.request.Request(broker.rstrip("/") + "/draw", data=body, method="POST",
                                headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(rq, timeout=240) as r:
            d = _j.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        try:
            d = _j.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            d = {"ok": False, "error": "HTTP %s" % e.code}
    except Exception as e:
        return "⛔ 连不上出图通道（%s）：%s" % (type(e).__name__, e)
    if not d.get("ok"):
        return "⛔ 出图失败：%s" % (d.get("error") or "未知错误")
    try:
        raw = base64.b64decode(d.get("b64") or "")
    except Exception as e:
        return "⛔ 图片数据坏了：%s" % e
    if not raw:
        return "⛔ 拿到的图是空的。"
    try:
        _os.makedirs(OUT_D, exist_ok=True)
        name = out or ("draw_%s.png" % _t.strftime("%m%d_%H%M%S"))
        path = name if _os.path.isabs(name) else _os.path.join(OUT_D, name)
        with open(path, "wb") as f:
            f.write(raw)
    except Exception as e:
        return "⛔ 图存不下来：%s" % e
    return "🖼 画好了：`%s`（%d 字节，模型 %s）" % (path, len(raw), d.get("model") or "?")


_SK_STOP = {"需要", "用于", "生成", "可以", "支持", "工具", "能力", "时候",
            "触发", "使用", "提供", "包括", "以及", "文件", "内容"}


def _skill_desc(path):
    """读 SKILL.md frontmatter 的 description（兼容 YAML 的 `>` / `|` 多行写法）。"""
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            txt = f.read(4000)
    except Exception:
        return ""
    m = re.match(r"---\s*\n(.*?)\n---", txt, re.S)
    fm = m.group(1) if m else ""
    mm = re.search(r"^description:\s*(.*)$", fm, re.M)
    if not mm:
        return ""
    first = mm.group(1).strip()
    if first in ("", ">", "|", ">-", "|-", ">+", "|+"):
        lines = []
        for ln in fm[mm.end():].split("\n"):
            if ln and not ln[0].isspace():
                break
            if ln.strip():
                lines.append(ln.strip())
        return " ".join(lines)
    return first


_SK_CACHE = {"t": 0.0, "items": []}


def _scan_skills():
    """扫技能目录的 SKILL.md frontmatter，返回 [(名字, description)]。
    换技能 = 往目录放/删文件，不改代码（dsh 的 dsh-skill-filesystem 同一套路）。"""
    if _SK_CACHE["items"] and time.time() - _SK_CACHE["t"] < 60:
        return _SK_CACHE["items"]
    out = []
    for root in SKILL_ROOTS:
        if not os.path.isdir(root):
            continue
        try:
            for d in sorted(os.listdir(root)):
                p = os.path.join(root, d, "SKILL.md")
                if os.path.isfile(p):
                    out.append((d, _skill_desc(p)))
        except Exception:
            pass
        if out:
            break  # 本地有就只用本地，不混
    _SK_CACHE["t"], _SK_CACHE["items"] = time.time(), out
    return out


def _kw_in(kw, t, gap=8):
    """关键词命中判定：整词命中，或它的字按顺序出现在 t 里（间隔<=gap）。
    2026-10-23 加：整词匹配抓不住"画一张柯基的图"对"画图"，保序子序列能抓。"""
    if not kw or not t:
        return False
    kw, t = kw.lower(), t.lower()
    if kw in t:
        return True
    if len(kw) < 2:
        return False
    i = t.find(kw[0])
    while i >= 0:
        j = i + 1
        ok = True
        for ch in kw[1:]:
            k = t.find(ch, j, i + gap)
            if k < 0:
                ok = False
                break
            j = k + 1
        if ok:
            return True
        i = t.find(kw[0], i + 1)
    return False

def _skill_match(task):
    """一个判定，三处共用：SKILL_MAP 关键词命中、技能名出现在任务里、描述里的词命中。"""
    t = task or ""
    hits = []
    for name, kws in SKILL_MAP.items():
        for kw in kws.split():
            if _kw_in(kw, t):
                hits.append(name)
                break
    for nm, desc in _scan_skills():
        if nm in hits:
            continue
        if nm.lower() in t.lower():
            hits.append(nm)
            continue
        kws = [w for w in re.findall(r"[\u4e00-\u9fa5]{2,4}|[A-Za-z]{4,}", desc or "")
               if w not in _SK_STOP]
        if kws and any(_kw_in(k, t) for k in kws):
            hits.append(nm)
    return hits


def skills_manifest(task=""):
    """把技能清单注进每轮上下文（dsh 的 skill 插件同一做法）。
    任务和某技能对得上，就在那一行标出来——不指望模型自己比对。"""
    skills = _scan_skills()
    if not skills:
        return ""
    hits = set(_skill_match(task)) if task else set()
    lines = ["\n\n## 技能清单（从 " + SKILL_ROOTS[0] + " 自动扫；加技能=放目录，不用改代码）"]
    for nm, desc in skills:
        d = (desc or "").replace(chr(10), " ")
        flag = ("  ← 对得上，先 <skill>" + nm + "</skill> 读全文") if nm in hits else ""
        lines.append(f"- {nm} — {d[:70]}{flag}")
    if hits:
        lines.append("（标了「对得上」的，先读说明书再动手，别自己瞎猜。）")
    return "\n".join(lines)


def skill_read(name):
    """先读本机技能书（~/agent/skills），没有再走沙箱。换手机带着目录就能用。"""
    for root in SKILL_ROOTS:
        p = os.path.join(root, name, "SKILL.md")
        if os.path.isfile(p):
            try:
                with open(p, encoding="utf-8", errors="ignore") as f:
                    return f.read()[:12000]
            except Exception:
                pass
    p = os.path.join(SKILL_ROOTS[0], name, "SKILL.md")
    try:
        with open(p, encoding="utf-8", errors="ignore") as f:
            return f.read()[:12000]
    except Exception:
        return "(没有这个技能)"

# NOTE_D 在文件头已定义（AJ_HOME/notes）

def note_write(task, cmds, ans=""):
    """收尾留痕：这次摸到哪条路。下一轮开局带出来 —— 等价于 dsh 反复改 AGENTS.md。"""
    try:
        cmds = [str(c).strip() for c in (cmds or []) if c and str(c).strip()]
        if not cmds:
            return ""
        os.makedirs(NOTE_D, exist_ok=True)
        p = os.path.join(NOTE_D, time.strftime("%Y-%m-%d") + ".md")
        buf = "\n## %s %s\n" % (time.strftime("%H:%M"), (task or "").replace("\n", " ")[:80])
        seen = set()
        for c in cmds:
            k = c[:80]
            if k in seen:
                continue
            seen.add(k)
            buf += "- `%s`\n" % c[:200]
            if len(seen) >= 6:
                break
        if ans:
            buf += "- 收尾：%s\n" % ans.replace("\n", " ")[:160]
        with open(p, "a", encoding="utf-8") as f:
            f.write(buf)
        return p
    except Exception:
        return ""

def note_recall(maxlines=20):
    """把最近几天自己留的路捞出来，摆在开局最前面。"""
    try:
        fs = sorted(f for f in os.listdir(NOTE_D) if f.endswith(".md"))[-3:]
        lines = []
        for f in fs:
            with open(os.path.join(NOTE_D, f), encoding="utf-8", errors="ignore") as fh:
                lines += [x.rstrip() for x in fh]
        lines = [x for x in lines if x.strip()][-maxlines:]
        if not lines:
            return ""
        return "\n\n## 以前摸到的路（自己留的笔记，能直接用就别重摸）\n" + "\n".join(lines)
    except Exception:
        return ""

def skill_inject(task, maxn=2, maxchars=2200):
    """命中的技能，说明书正文直接摆进本轮上下文——不指望模型主动 <skill>。
    2026-10-23 立：清单标了「对得上」它也不读，那就把正文递到眼前。
    换技能 = 换目录里的文件，代码一行不改。"""
    names = [s[0] for s in _scan_skills()]
    try:
        hits = [h for h in _skill_match(task or "") if h in names][:maxn]
    except Exception:
        return ""
    out = []
    for nm in hits:
        body = skill_read(nm) or ""
        if not body or body.startswith("(没有这个技能"):
            continue
        body = re.sub(r"^---\s*\n.*?\n---\s*\n", "", body, count=1, flags=re.S).strip()
        if not body:
            continue
        out.append("\n\n## 这一轮的说明书：%s\n（自动从技能目录取出，按它做，别自己想一套）\n%s"
                   % (nm, body[:maxchars]))
    return "".join(out)


SKILL_MAP = {
    "sousuo": "搜索 搜一下 查一下 找一下 联网 新闻 资讯 资料 最新",
    "web-search": "搜索 搜一下 查一下 联网 最新 实时 政策 价格 活动",
    "vision": "看图 图片 照片 截图 识图 OCR 图里 识别",
    "video-understand": "视频 看懂 分析视频 抖音 短片",
    "fanyi": "翻译 英文 中文 译成 多语",
    "docx": "word 文档 docx 报告 说明书",
    "pptx": "ppt 幻灯片 演示 汇报 演示文稿",
    "pdf": "pdf 排版 打印件 导出",
    "xlsx": "excel 表格 数据表 清单 账目",
    "charts": "图表 折线 柱状 饼图 可视化 数据图",
    "image-generation": "画图 生成图片 插画 作图 图片生成",
    "free-tts": "朗读 语音 配音 tts 转语音 念一遍",
    "jiyao": "纪要 会议 录音 整理 讨论 记录",
    "biji": "笔记 整理笔记 摘录",
    "dushu": "读书 书摘 读后感 书籍",
    "gonglue": "攻略 行程 旅游 路线",
    "hetong": "合同 协议 条款 审阅",
    "changwen": "长文 扩写 公众号 文章",
    "jiyao": "纪要 会议 录音",
    "xiaoshuo": "小说 网文 续写 章节 大纲",
    "xinmeiti": "新媒体 小红书 文案 种草 标题党",
    "biaotou": "标题 起标题 爆款标题",
    "yuepu": "乐谱 简谱 五线谱 曲子",
    "shengcheng": "生成配方 纯脚本",
    "sandbox-ops": "沙箱 重启 服务 隧道 端口 故障 排障",
    "scripts": "小脚本 一次性 批量 改名 统计 转换",
    "skill-creator": "新技能 造技能 写技能",
}


def is_daily_question(task):
    """问今天/刚才干了什么这一类，答案一定在 daily 里。"""
    kw = ("今天干了", "今天改", "今天做", "刚才改", "刚才做", "今天发生",
          "记录了什么", "今天怎么了", "今天发生什么")
    t = (task or "")
    return any(k in t for k in kw)


def skill_hint(task):
    """给提示用：命中的技能名（最多 4 个）和技能总数。只给名字，正文让它自己读。"""
    hits = _skill_match(task or "")
    names = [s[0] for s in _scan_skills()]
    if names:
        hits = [h for h in hits if h in names]
    return hits[:4], (len(names) or len(SKILL_MAP))


# ---------------- 反编造闸：未查证的历史结论不许说、不许记 ----------------
# 2026-10-21 实测：被问"sandbox_key 在哪"，它直接编了个不存在的目录，
# 还企图用 <mem> 把假结论写进 facts.md。提示词里"未 rg 过不得回答"只是劝告，
# 压不住小模型——这里改成硬检查：宿主问的是历史/位置/端口类问题时，
# 本轮没真去读过记忆文件，结论既不许交付，也不许落 facts/lessons/tasks。
_VERIFY = {"need": False, "read": False, "asks": 0, "blocked": 0}

_MEMFILES = ("总纪要.md", "core.md", "lessons.md", "facts.md", "boundary.md",
             "tasks.md", "daily", "旧记忆", "记忆录")


def _is_mem_read(cmd):
    """这条命令是不是真在读记忆文件"""
    c = cmd or ""
    if not any(f in c for f in _MEMFILES):
        return False
    return any(t in c for t in ("rg ", "cat ", "head ", "tail ", "sed ", "less ", "grep ", "ls "))


# 对话控制词：这些是"催促 / 打断 / 收手"，不是要查资料的问题。
# 宿主原话（2026-10-21 22:0x）：他要的是"停止、继续"这种话不该让 AJ 跑活。
_BEGIN, _END, _DONE = "<" + "begin", "<" + "/begin", "<" + "done>"


# 2026-10-22 宿主质问：「为什么每次任务都要去沙箱搜索？有没有不需要搜索的？
# 又为什么要去搜索呢」——问得对，之前是无条件 ssh 进沙箱 rg 一趟，
# 你说"帮我清缓存"它也要先翻一遍档案，白跑一趟还可能翻出不相干的东西带偏。
# 现在先判：这条任务是不是在问"以前怎么定的/在哪/哪个端口"，不是就不去。
_ARCHIVE_Q = re.compile(
    r"之前|以前|上次|上回|那次|当时|原来|当初|早先|之前定的|说过的|说定了|定过|定了|"
    r"记过|记的|记录里|档案|记忆|纪要|在哪|哪里|哪个|哪台|多少|端口|路径|目录|位置|"
    r"谁改的|怎么弄的|有没(有)?讲过|查过|翻过|老规矩|老约定|老账|"
    # 2026-10-22 补：问"怎么用/用法/是什么/规则"本质都是问历史，原来漏了这些直接不检索。
    r"怎么用|如何用|用法|怎么使|怎么调|怎么跑|怎么起|怎么开|"
    r"是什么|是啥|指什么|什么意思|规则是什么|规矩是什么|注意事项")
# 明确不需要翻档案的：执行类、当前状态类、闲聊、控制语
_NO_ARCHIVE = re.compile(
    r"^(运行|跑|执行|装|安装|启动|重启|停|关|删|清|清理|清空|建|创建|新建|改|修改|写|生成|"
    r"翻译|打开|下载|上传|整理|转换|压缩|解压|截图|画|做|帮我做|给我做)|"
    r"现在|当前|此刻|看看现在|状态|通不通|在不在|几个进程|占用|剩余|多少G|多少M")


def need_archive(task):
    """这条任务值不值得 ssh 进沙箱 rg 一趟。返回 (bool, 理由)。

    只读本地词表判断，零成本零延迟。判不准时宁可搜（多花 15 秒，别答错历史），
    但执行类/状态类/闲聊/控制语明确不搜。
    """
    t = (task or "").strip()
    if not t or len(t) < 3:
        return False, "太短"
    if _is_control(t):
        return False, "控制语"
    if _CHITCHAT.search(t):
        return False, "闲聊/身份"
    if _NO_ARCHIVE.search(t):
        return False, "执行/状态类"
    if _ARCHIVE_Q.search(t):
        return True, "问历史/位置"
    return False, "无档案线索"


def _search_cmd(pat, per_file=2, files_only=False):
    """有 ripgrep 用 rg；没有就退回 busybox grep（Alpine 精简镜像常见）。"""
    q = shlex.quote(MEMD)
    if HAS_RG:
        if files_only:
            return "rg -l -i --no-heading '%s' %s -g '!*.bak*' 2>/dev/null | head -1" % (pat, q)
        return ("rg -n -i --no-heading -m %d '%s' %s -g '!*.bak*' -g '!*.stale*' "
                "-g '!CHANGELOG*' 2>/dev/null | head -40" % (per_file, pat, q))
    if files_only:
        return "grep -rl -i -E '%s' %s 2>/dev/null | head -1" % (pat, q)
    return ("grep -rn -i -E -m %d --exclude='*.bak*' --exclude='*.stale*' "
            "--exclude='CHANGELOG*' '%s' %s 2>/dev/null | head -40" % (per_file, pat, q))


def mem_search(task, limit=4):
    """宿主 2026-10-21 定案：「不用加让他忘了可以搜索，然后再回答」。

    他接受"每条任务干净开局"（不塞历史），但要的是**该查的时候自己会查**。
    这里给模型一份可搜索的档案索引（只有索引和命中行，不是内容），
    让它在答之前自己决定要不要 <sb> 把原文拉出来。

    范围：沙箱 记忆录 / 每日记录 / 工作区 / 旧记忆 / Termux 的 ~/agent/memory。
    全程只读，不写文件。查不到就返回空——宁可少说，不许瞎猜。
    """
    t = (task or "").strip()
    if len(t) < 2:
        return ""
    _ok, _why = need_archive(t)
    if not _ok:
        return ""
    # 检索词怎么拼很关键（2026-10-21 踩了两次）：
    #  ① 直接按 2~6 字切会切出"我们之前定的""沙箱端口是哪"这种整段，rg 当成一整串去匹配 → 永远搜不到
    #  ② 切成双字又太碎，噪音大
    # 折中：中文按重叠双字切（无词边界的唯一可靠土办法），再用"档案里检索得到"筛一遍。
    stop = {"什么", "怎么", "为什么", "哪里", "哪个", "可以", "能不能", "是不是",
            "现在", "刚才", "然后", "帮我", "一下", "的话", "这个", "那个",
            "怎么改", "有没有", "请问", "到底", "究竟", "之前", "以前", "说过", "记得"}
    # 中文没有词边界，正则按长度硬切必然切歪（'我们之前''定的沙箱'）。所以：
    # 整句切成重叠双字，再按"档案里到底有没有这个词"过滤——有才留，没有就丢。
    han = re.sub(r"[^\u4e00-\u9fa5A-Za-z0-9_.-]", " ", t)
    words = []
    for seg in han.split():
        words.extend(seg[i:i + 2] for i in range(max(1, len(seg) - 1)))
        if len(seg) <= 5 and seg not in words:
            words.append(seg)
    words.extend(re.findall(r"[A-Za-z][A-Za-z0-9_.-]{2,}", t))
    # 2026-10-22：带下划线/连字符/点的组合词整体优先（sandbox_key / push.sh / 127.0.0.1）。
    # 原来 'sandbox_key' 被切成 'sandbox'+'key'，OR 匹配下 'key' 的杂讯把真答案挤出前 4 行。
    combo = re.findall(r"[A-Za-z][A-Za-z0-9]*(?:[_.-][A-Za-z0-9]+)+", t)
    words = combo + words
    keys = []
    for w in words:
        if w in stop or w in keys:
            continue
        # 2026-10-22：纯英文 2 字符以下（sh/go/py/ls）或中文单字，作为 OR 关键词噪音远大于收益。
        # 'push.sh 怎么用' 就是被 'sh' 拖垮的：'sh' 在库里命中上千条，把 push.sh 的真答案挤掉。
        if len(w) < 2:
            continue
        if re.fullmatch(r"[A-Za-z0-9_.-]+", w) and len(w.replace(".", "").replace("_", "").replace("-", "")) < 3:
            continue
        keys.append(w)
    # 只留"档案里真出现过"的词。整句去 rg 一遍，命中过的才算关键词——
    # 这样 '之前''定的' 这类泛词自然被淘汰，'沙箱''端口' 留下。
    if keys:
        probe = "|".join(re.escape(k) for k in keys[:24])
        try:
            seen = sb(_search_cmd(probe, files_only=True), 45) or ""
        except Exception:
            seen = ""
        if seen.strip():
            keys = keys          # 至少有一个词在档案里，全部保留（rg OR 只报命中的）
        else:
            # 一个都没命中：只留最长的两个词，避免把噪音全丢给模型
            keys = keys[:2]

    if not keys:
        return ""
    alt = "|".join(re.escape(k) for k in keys)
    # 原来固定扫 CHANGELOG 这类流水，大文件一命中就把真正的规矩挤没了。
    # rg 按 -g 顺序输出文件，所以把权威目录放最前、流水目录排最后。
    cmd = _search_cmd(alt, per_file=2)
    try:
        hits = sb(cmd, 60) or ""
    except Exception:
        hits = ""
    if hits.startswith("[进沙箱失败]"):
        hits = ""
    lines = []
    for ln in hits.splitlines()[:limit * 2]:
        ln = ln.strip()
        if not ln or ln[0] not in "/":
            continue
        ln = ln.replace(MEMD + "/", "")
        lines.append("  " + ln[:110])
    # 2026-10-21 23:5x 精简：档案地图只留一行（记忆录/每日/旧记忆三处都在这三行命令里），
    # 命中行最多 4 条、每条 110 字。原来光注入块就 1469 字，比整个系统提示还长。
    tip = ("\n【先搜索再回答】查不到就说查不到，不许编。\n"
           "  <sh>rg -n '关键词' " + MEMD + "</sh>\n")
    if lines:
        tip += "  本题预检命中（引用前先读原文）：\n" + "\n".join(lines[:4]) + "\n"
    else:
        tip += "  本题没预检到东西，动手前自己 rg 一遍。\n"
    return tip


def _ctrl_prompt(task):
    """控制语 → 一句极简指令。不走工具、不查记忆，最多一轮。"""
    t = (task or "").strip(_CTRL_STRIP)
    if any(w in t for w in ("停止", "停下", "别干了", "算了", "不用了", "取消", "退出", "结束", "够了")):
        return "宿主让收手：一句话说清现在停在哪儿、哪些没做完，然后用 " + _DONE + "收尾。不许再调工具。"
    if any(w in t for w in ("继续", "接着", "说下去", "往下说", "然后呢", "重来", "再说一遍")):
        return "宿主让你接着说/接着干：看对话上文，直接往下接，别从头重来、别重新查资料。说完了用 " + _DONE + "收尾。"
    if any(w in t for w in ("快", "赶紧", "等着", "干嘛呢", "干什么呢", "卡住")):
        return "宿主在催/问状态：一句话说你现在在干什么、卡在哪。别调工具。"
    return "宿主这句话是简短回应/寒暄：一句话应答就好，别调工具、别展开。"


def _quick_control(task):
    """把控制语当一句话的单轮任务跑：不带工具、不查证、不打回。"""
    sysmsg = "你是 Aj。回答要短，说人话。"
    if any(w in (task or "") for w in ("忘了", "不记得", "记不得", "想不起")):
        # 宿主说"忘了"就是在告诉你：去档案里搜，别硬撑
        sysmsg += ("\n确实不记得就说忘了，然后去搜档案再答："
                   "<sh>rg -n '关键词' " + MEMD + "</sh>")
    msgs = [{"role": "system", "content": sysmsg},
            {"role": "user", "content": _ctrl_prompt(task)}]
    try:
        out = call_model(msgs)
    except Exception as e:
        print(f"  [控制语应答失败] {e}")
        return 1
    kind, val = parse(out)
    if kind == "done":
        print("=" * 46)
        print(val)
    else:
        # 没包动作标签也没关系：控制语本来就不该动手，原样交付
        txt = (val or out or "").strip()
        print("=" * 46)
        print(txt or "（没话说）")
    return 0


def _needs_verify(task):
    """宿主这句问的是历史/位置/端口/约定吗——这类答案必须查过才能说"""
    t = (task or "").strip()
    # 2026-10-21 22:0x：宿主在跑的过程中说"继续回答""停止任务""算了不用了"这类，
    # 是**对话控制**不是提问，一句话都不该触发查证闸——实测它把这几个字当内容，
    # 跑去 rg 搜了一堆记忆文件，白烧几十轮还被闸拦着不让交付。
    if _is_control(t):
        return False
    kws = ("在哪", "哪里", "哪个", "什么位置", "路径", "端口", "怎么起", "怎么启动",
           "怎么配", "以前", "之前", "上次", "当时", "定过", "拍板", "谁定的",
           "有没有", "还在吗", "删了", "备份", "放哪", "叫什么", "多少",
           # 2026-10-21：涉及自身/沙箱关系的问题同样不许凭印象答（实测它答"你怎么进沙箱"
           # 时凭空认错，又说"已成功进入，pwd 是 /root"，全是编的）
           "沙箱", "技能", "记忆", "模型", "分组", "怎么进", "怎么进沙箱", "能看", "能不能",
           "你是什么", "你是谁", "你住", "你怎么跑", "你怎么运行", "前端", "网页端",
           # 2026-10-21 宿主定案"忘了可以搜"：问过去的事，就得先去档案里搜
           "记得", "说过", "交代过", "聊过", "提过", "上次说", "刚才说", "之前说",
           "档案", "记录", "历史", "台账", "教训", "为什么这么定", "谁的意思")
    return any(k in t for k in kws)


# 对话控制词：这些是"催促 / 打断 / 收手"，不是要查资料的问题。
# 判据是**整句**（去掉标点后）几乎只有这几个词，命中就整句放行；
# 混在真问题里的（"停止后备份在哪"）不算，照常查证。
_CTRL_WORDS = ("继续", "继续回答", "继续吧", "接着说", "说下去", "往下说", "然后呢",
               "停止", "停止任务", "停下", "停", "别干了", "算了", "不用了", "不要了",
               "取消", "退出", "结束", "就这样", "好了", "行了", "够了",
               "快", "快点", "赶紧", "等着", "在吗", "嗯", "哦", "行", "好", "可以",
               "对的", "不对", "错了", "重来", "再说一遍", "谢谢", "谢了",
               "为什么停了", "你怎么停了", "干嘛呢", "干什么呢", "卡住了", "卡住")
_CTRL_STRIP = " \t\r\n，。！？、,.!?~～…：:；;\"'“”‘’（）()【】[]"


def _is_control(text):
    """整句是纯控制语/寒暄吗（"继续""停止任务""算了不用了"）。"""
    t = (text or "").strip(_CTRL_STRIP)
    if not t or len(t) > 12:
        return False
    # 单个词的控制语不能只看"剥空了"：'好的'/'可以' 的每个字都在名单里，
    # 会被逐字剥空误判成控制语。要求至少完整命中一个词。
    if not any(w in t for w in _CTRL_WORDS):
        return False
    # 全量命中才放行：把句中的控制词反复剥掉，剥空了就算
    rest = t
    for w in sorted(_CTRL_WORDS, key=len, reverse=True):
        rest = rest.replace(w, "")
    rest = rest.strip(_CTRL_STRIP)
    return rest == ""



# ---------------- 记忆 ----------------
def mem_add(kind, text):
    os.makedirs(MEMD, exist_ok=True)
    p = os.path.join(MEMD, {"fact": "facts.md", "lesson": "lessons.md"}.get(kind, "tasks.md"))
    try:
        with open(p, "a") as f:
            f.write(f"- [{time.strftime('%m-%d %H:%M')}] {text}\n")
        return "(已记)"
    except Exception as e:
        return f"(记失败: {e})"


# ---- 按天日志：保留最近 KEEP_DAYS 天，删旧留新 ----
KEEP_DAYS = 7
DAILY_D = os.path.join(MEMD, "daily")


def daily_add(role, text):
    """写今天的日志，然后把 7 天前的删掉。
    facts/lessons/tasks 三个长期文件不参与轮转，永不删。

    2026-10-22 修：原来用本地 open() 往 DAILY_D 写，但 Termux 侧 /var/minis 整个不存在
    （记忆录物理上只在沙箱私有区，见第 23 行注释），os.makedirs 直接抛异常又被
    `except Exception: return ""` 吞掉——daily/ 从 10-21 就再没写进过任何一条。
    现在一律走 sb()：墙内直接跑，Termux 侧 ssh 进沙箱跑。写没写成会返回原文，不再静默。"""
    line = text.replace("\n", " ").strip().replace("'", " ")
    if len(line) > 800:
        line = line[:800] + "…"
    day = time.strftime("%Y-%m-%d")
    entry = "- [%s] (%s) %s" % (time.strftime("%H:%M"), role, line)
    p = os.path.join(DAILY_D, day + ".md")
    cmd = ("mkdir -p %s && printf %s %s >> %s && tail -n 1 %s"
           % (shlex.quote(DAILY_D),
              shlex.quote("%s\\n"), shlex.quote(entry),
              shlex.quote(p), shlex.quote(p)))
    try:
        r = (sb(cmd, timeout=60) or "").strip()
        if not r or "error" in r.lower() or "失败" in r:
            return "[daily写失败] " + r[-300:]
        rot = rotate_daily()
        return ("已写 daily: %s\n%s" % (r[-120:], rot)).strip()
    except Exception as e:
        return "[daily写失败] %r" % (e,)

def rotate_daily():
    """只留最近 KEEP_DAYS 个 .md，更早的归档到 daily-archive/ 再删。只碰 daily/ 这一层。
    2026-10-22：同 daily_add，改走 sb()；本地 os.listdir 在 Termux 侧看不到沙箱目录。"""
    AR_D = os.path.join(MEMD, "daily-archive")
    cmd = ("mkdir -p %s && cd %s && ls -1 *.md 2>/dev/null | sort | head -n -%d | "
           "while read f; do mv -f \"$f\" %s/ 2>/dev/null && echo \"归档 $f\"; done"
           % (shlex.quote(AR_D), shlex.quote(DAILY_D), KEEP_DAYS, shlex.quote(AR_D)))
    try:
        r = (sb(cmd, timeout=60) or "").strip()
        arch = [x for x in r.splitlines() if x.startswith("归档 ")]
        return ("(轮转归档 %d 个: %s)" % (len(arch), ", ".join(a[3:] for a in arch))) if arch else ""
    except Exception:
        return ""


def mem_recall(task):
    """只给指针，不喂内容：告诉它去哪查，去不去由它决定"""
    # 2026-10-21：闲聊/身份类不需要任何记忆提示，塞进去只会把模型带偏
    if _CHITCHAT.search(task or ""):
        return ""
    # 2026-10-22：执行类/状态类任务不给"旧账指针"。以前只要切得出词就往里塞一段
    # "跟你这次任务可能相关的旧账…rg 命令"，模型照着跑一趟，白烧一轮还可能被带偏。
    if not need_archive(task)[0]:
        words = []
    else:
        words = re.findall(r"[\u4e00-\u9fa5]{2,4}|[A-Za-z]{3,}", task)[:6]
    head = ""
    sk = skill_hint(task)
    sk = sk[0] if isinstance(sk, tuple) else sk
    if sk:
        head += ("\n【这次八成用得上技能：%s\n"
                 "  先 <skill>%s</skill> 读说明书，再照它说的调，别自己从零拼。】"
                 % ("、".join(str(x) for x in sk), str(sk[0])))
    # 2026-10-22：出图链路已改，但旧技能书的匹配词还挂着"画图/生成图片"。
    # 只删关键词不够——它会顺着 skill_hint 读到旧说明照跑，然后报缺 key。
    # 这段必须放 if sk 外面：'出图怎么走' 可能一个技能都不命中，放里面就等于没加。
    if is_daily_question(task):
        import subprocess as _sp
        try:
            _d = _sp.check_output(["date", "+%F"], text=True).strip()
        except Exception:
            _d = ""
        return (head + "\n【宿主问的是「今天干了什么/改了什么」，不是要你搬日志。\n"
                "  daily 只是证据来源，不是答案；流水里记的是问答，不是改动。\n"
                "  正确做法：先定出今天动过哪些文件——\n"
                "    date +%F\n"
                "    find " + AGENTD + " -mtime -1 -type f 2>/dev/null   # 今天动过（busybox 不认 -newermt）\n"
                "    ls -t ~/agent/  按 mtime 看最近改过哪些\n"
                "  再看 diff / 读内容，确认每一处改了什么、为什么改。\n"
                "  答法：按「文件 + 改了什么 + 为什么」列，验过的说验过，没验的说没验。\n"
                "  日志 " + MEMD + "/daily/" + _d + ".md 只用来核对时间点。】")
    if not words:
        return head
    # 2026-10-22 接上 mem_search：原来只给一条"自己去 rg"的空指针，rg 语句还得模型自己拼，
    # 小模型拼成一整句永远搜不到，搜出空结果也照样交付。现在由代码拼好检索词并预跑一遍，
    # 把命中的行直接摆出来——模型不用再拼命令，只需要读原文核对。
    _probed = mem_search(task)
    if _probed:
        return head + _probed
    return (head + "\n【跟你这次任务可能相关的旧账，自己决定要不要翻：\n"
            "  你的记忆库: " + MEMD + "\n"
            "  rg -n \"" + "|".join(words[:3]) + "\" " + MEMD + " 2>/dev/null | head -20】")



# ---------------- 说话判断：谁在说、冲谁说、要什么 ----------------
# 宿主一句话进来，先判它是哪类，别把"批评"当"任务"、把"回答"当"新活"。
# 纯规则、零模型调用，快且稳；判不准就按 critique 走（先认、先改、再报）。
_CRIT_PAT = re.compile(
    r"你又说|又问了|又说了一遍|说过了|讲过|不是问你|不是在问|别每次|又要我|"
    r"你怎么|你搞错|你弄错|说了多少次|提醒过|不是让你|谁让你|"
    r"批评|骂你|是在告诉你|别解释|听不懂")
_QUESTION_PAT = re.compile(r"^(请问|能不能|可不可以|要不要|是不是|为什么|咋|怎么|多少|哪|啥|what|how|why|can you|do you)|[?？]\s*$")
_TELL_PAT = re.compile(r"^(把|帮我|给我|做|查|搜|生成|写|改|删|跑|装|翻译|打开|清|看)")
_VERDICT_PAT = re.compile(r"^(好|行|可以|同意|对|没错|不要|不行|算了|停掉就行|就这样|继续|别|不用|嗯|ok|yes|no)", re.I)

def classify(text):
    """返回 (kind, hint). kind in task|question|critique|verdict|chat"""
    t = (text or "").strip()
    if not t:
        return "chat", ""
    if ("不是" in t and "问你" in t) or ("是在批评" in t):
        return "critique", ("【这不是问题，是宿主在教你分辨：他在指认你犯了什么错，"
                            "并要求你自己修好判断机制。认下来→改根因→报证据，别解释、别反问。】")
    if _CRIT_PAT.search(t):
        return "critique", ("【这是批评/指认，不是提问。他是在说你做错了什么。"
                            "要你做的是：认错→说清错在哪→改掉根因→报证据。绝不当成问题去解释或反问。】")
    if len(t) <= 12 and _VERDICT_PAT.match(t):
        return "verdict", "【这是对上一轮的裁决/回答，直接照办，不要重新讨论、不要再问一遍。】"
    if _QUESTION_PAT.search(t) and not _TELL_PAT.match(t):
        return "question", "【这是提问，直接回答，不要乱动手。】"
    if _TELL_PAT.match(t) or len(t) > 12:
        return "task", ""
    return "chat", ""

def tag_user(text):
    """把一句话包成带类型标签的 user 消息"""
    kind, hint = classify(text)
    head = {"task": "任务：", "question": "提问（请直接回答，别动手）：",
            "critique": "宿主在批评你（不是提问，别解释别反问）：",
            "verdict": "宿主裁决：", "chat": ""}[kind]
    return f"{head}{text}" + (("\n" + hint) if hint else ""), kind

# ---------------- 模型 ----------------

# ---- 本地直连退路：沙箱模型全挂时，用本地 key 直接发 HTTP ----
def call_local(msgs, timeout=180):
    """直连厂商 API 的退路已删除：key 只在 broker 里，本文件不许出现 key 常量。"""
    raise RuntimeError("本机没有直连通道，模型请求一律走 broker（%s）" % BROKER)


_EID_CACHE = {}


def _entry_id(name):
    """App 版没有多模型路由（模型由 broker 决定）。保留函数给旧调用点。"""
    return None


def _chat_entries():
    return set()


def _read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def _parent_order():
    """父 AJ（App 本体）排好的模型顺序：它写出的 models.json 就是唯一口径。
    子 AJ 不排序、不记账，照这个顺序试。"""
    d = _read_json(CONF_MODELS)
    out = []
    for m in d if isinstance(d, list) else []:
        n = str((m or {}).get("name") or "").strip() if isinstance(m, dict) else str(m).strip()
        if n and n not in out:
            out.append(n)
    return out


def _report(name, ok, err=""):
    """把自己这一轮的真实结果回报给父 AJ，让它更新唯一的那本账。
    只追加写一条，绝不阻塞、绝不抛。"""
    name = str(name or "").strip()
    if not name:
        return
    try:
        with open(CONF_REPORT, "a", encoding="utf-8") as f:
            f.write(json.dumps({"name": name, "ok": bool(ok), "err": str(err or "")[:160],
                                "ts": int(time.time()), "from": "web"}, ensure_ascii=False) + "\n")
    except Exception:
        pass


def _task_hint():
    """当前任务文本（只给父 AJ 做任务偏好用，这里不参与排序）。"""
    return (LAST_MODEL.get("task") or "").strip()


def _build_chain():
    """候选链 = 父 AJ 写出的顺序（父已按健康度/档位排好）。
    拿不到清单就退回 broker 背后那一个模型。"""
    cands = _parent_order()[:12]
    if cands:
        return cands
    return [os.environ.get("AJ_MODEL", "") or "broker"]


def _weak_hit(why):
    """被反编造闸拦下 = 这个模型在凭想象干活。拦够次数就切付费兜底。"""
    _WEAK["hits"] += 1
    if _WEAK["hits"] >= _WEAK_LIMIT and not _WEAK["fired"] and PAID_FALLBACK:
        _WEAK["fired"] = True
        print(f"  [降级] 这个模型已{_WEAK['hits']}次凭想象干活（{why}），本任务剩余轮次切付费兜底")




IMG_DEFAULT = ""
IMG_GROUPS = ()


def _img_cands():
    return []


def img_gen_api(prompt, model=None):
    return img_gen(prompt)


def img_route(prompt):
    return img_gen(prompt)


_RETRY_RE = re.compile(r"429|rate.?limit|too many requests|timeout|timed out|"
                       r"50[234]|overload|busy|unavailable|空输出|非 JSON", re.I)


def _retryable(text):
    """dsh-llm-retry 口径：只对瞬时错误重试；鉴权/不存在类错误直接换模型。"""
    return bool(_RETRY_RE.search(text or ""))


def _sleep_backoff(attempt):
    """指数退避 + 抖动：2s、4s（封顶 8s），×0.7~1.3。"""
    base = min(2 * (2 ** attempt), 8)
    jitter = 0.7 + (int(time.time() * 1000) % 601) / 1000.0
    time.sleep(base * jitter)


def _broker_text(raw):
    """从 broker 的 OpenAI 兼容响应里取正文。返回 (text, err)。"""
    try:
        d = json.loads(raw)
    except Exception:
        s = (raw or "").strip()
        if s and not s.startswith("{"):
            return s, ""          # 纯文本直出
        return "", "broker 返回不是 JSON：%s" % s[:160]
    if not isinstance(d, dict):
        return "", "broker 返回结构异常"
    if d.get("error"):
        return "", str(d.get("error"))[:240]
    ch = (d.get("choices") or [{}])[0]
    c = (ch.get("message") or {}).get("content")
    if isinstance(c, list):
        c = "".join(str(x.get("text") or "") for x in c if isinstance(x, dict))
    if c is None:
        c = d.get("text")
    return (c or ""), ""


def call_model(msgs):
    """唯一模型出口：本机 broker（POST /chat，OpenAI 兼容）。

    key 由 broker 从环境变量管，本文件不读、不存、不打印任何 key。
    失败抛 RuntimeError（调用方转成一句干净的错误，不往外吐栈）。
    """
    model = HOST_PIN.get("model") or ""
    payload = {"messages": msgs, "temperature": 0.3,
               "max_tokens": int(os.environ.get("AJ_MAX_TOKENS", "4096"))}
    if model:
        payload["model"] = model
    body = json.dumps(payload, ensure_ascii=False).encode()
    last = ""
    _mb_failed = set()      # 本轮已经记过失败的模型名（避免重试把台账刷爆）
    for attempt in range(3):
        rq = urllib.request.Request(BROKER + "/chat", data=body,
                                    headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(rq, timeout=BROKER_TIMEOUT) as r:
                raw = r.read().decode("utf-8", "replace")
                _used = (r.headers.get("X-AJ-Model") or "").strip()
        except urllib.error.HTTPError as e:
            try:
                j = json.loads(e.read().decode("utf-8", "replace"))
                detail = str(j.get("error") or j.get("message") or "")[:200]
                for _m in (j.get("tried") or []):
                    if _m and _m not in _mb_failed:
                        _mb_failed.add(_m)
                        _report(_m, False, detail or ("HTTP %s" % e.code))
            except Exception:
                detail = ""
            last = "broker HTTP %s %s" % (e.code, detail)
            if e.code in (400, 401, 402, 403, 404):
                break
            time.sleep(2 * (attempt + 1))
            continue
        except Exception as e:
            last = "连不上 broker（%s）：%s" % (BROKER, type(e).__name__)
            _report(model or os.environ.get("AJ_MODEL", ""), False, last)
            time.sleep(2 * (attempt + 1))
            continue
        txt, err = _broker_text(raw)
        if txt and txt.strip():
            # broker 用 X-AJ-Model 告诉我们它最后真用了哪个模型 → 记成功
            _name = _used or model or os.environ.get("AJ_MODEL", "") or "broker"
            _report(_name, True)
            if _name not in LAST_MODEL["used"]:
                LAST_MODEL["used"] = [_name]
            LAST_MODEL["final"] = _name
            return txt
        _report(_used or model, False, err or "空输出")
        last = err or "broker 返回空文本"
        time.sleep(1)
    raise RuntimeError(last or "broker 没有响应")


def _clean(text):
    """清掉模型吐出来的 DSML / 特殊包裹标记，别让它们混进命令。
    见过的形态： <|DSML|tool_calls|  、｜DSML｜  、<|/DSML|>  、deepseek 的 ｜｜｜DSML｜｜｜"""
    if not text:
        return ""
    t = text
    # 统一的 DSML 变体：半角|、全角｜、各种包裹
    t = re.sub(r"[<｜|]+\s*/?\s*DSML\s*[｜|>]*", "", t, flags=re.I)
    # deepseek 内部工具标记 ｜｜｜DSML｜｜｜
    t = re.sub(r"[｜|]+\s*/?\s*(tool_calls|tool\u2581calls|begin\u2581of\u2581sentence)\s*[｜|]+", "", t, flags=re.I)
    # 残留的 <| |>
    t = re.sub(r"<\|\s*\|>", "", t)
    return t


# 裸命令识别：第一个 token 必须是已知命令，避免把普通中文答复当命令跑
_CMD_RE = re.compile(r"^(?:date|ls|cat|rg|grep|head|tail|sed|awk|wc|du|df|free|ps|"
                     r"python3?|pip3?|node|npm|git|curl|wget|systemctl|apk|"
                     r"sh|bash|echo|touch|mkdir|cp|mv|rm|chmod|chown|ssh|scp|"
                     r"lsblk|mount|ss|netstat|uname|uptime|which|command|env|"
                     r"tar|gzip|unzip|jq|ffmpeg|convert|filesize)(?:\s|$)")

# 单词本身就是完整命令的（其余单个词一律当正文，不当命令）
_STANDALONE = {"date", "df", "free", "uptime", "uname", "ps", "env", "lsblk", "whoami"}
# 闲聊/身份/寒暄：这类不需要动文件，首轮就可交付，别逼它凑动作块
# （2026-10-21：定义已上移到 SYSTEM 之前，供 mem_recall 使用）

def _bare_cmd(t):
    """整条回复是不是一句裸命令（模型漏了 <sb> 标签）。
    有参数、或者本身是个真能跑的裸命令才收；单个 `python`/`cat` 这类词太像正文，宁可放过。"""
    s = (t or "").strip().strip("`").strip()
    if not s or "\n" in s or not _CMD_RE.match(s):
        return None
    return s if (" " in s or s in _STANDALONE) else None

def parse(text):
    text = _clean(text)
    for tag in ("done", "stuck", "ask", "plan", "skill", "read", "sh", "sb", "img"):
        m = re.search(rf"<{tag}>(.*?)</{tag}>", text, re.S)
        if m:
            return (tag, m.group(1).strip())
    m = re.search(r'<write\s+path="([^"]+)">(.*?)</write>', text, re.S)
    if m:
        return ("write", (m.group(1), m.group(2)))
    m = re.search(r'<mem(?:\s+kind="(\w+)")?>(.*?)</mem>', text, re.S)
    if m:
        return ("mem", (m.group(1) or "fact", m.group(2).strip()))
    # 兜底：模型漏了标签，整条回复就是一句裸命令。
    # 2026-10-21 实测 GLM-4-Flash 常直接吐 `date +%F`，旧代码判成 none →
    # 命令根本没跑，它再凭想象编答案。宁可当命令执行，也不能静默丢弃。
    _bare = _bare_cmd(text)
    if _bare:
        return ("sb", _bare)
    return ("none", text.strip())


_CHECKPOINT_PROMPT = """你是上下文压缩引擎。把下面的对话压缩成结构化检查点，让另一个模型能无缝接手。
用中文，只输出检查点本身，不调用工具、不加开场白。按这 8 节输出，没有内容的节写"无"：

## 用户目标
（原始和演变的目标；关键措辞原样引用）
## 关键技术点
## 文件与代码
## 错误与修复
## 待办
## 当前进展
## 下一步
## 关键上下文
"""


def _reqd_chat(messages, timeout=120):
    """旧的 reqd chat 通道（127.0.0.1:18790）已不存在，改走 broker。失败返回 None。"""
    try:
        body = json.dumps({"messages": messages, "max_tokens": 2048}).encode()
        rq = urllib.request.Request(BROKER + "/chat", data=body,
                                    headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(rq, timeout=timeout) as r:
            txt, err = _broker_text(r.read().decode("utf-8", "replace"))
        if txt:
            return txt
        print("  [compact] broker chat 返回异常: %s" % (err or "空"))
    except Exception as e:
        print("  [compact] broker chat 失败: %s: %s" % (type(e).__name__, e))
    return None


def compact_checkpoint(msgs):
    """dsh 式检查点：把中段对话压成 8 节结构化摘要。失败返回 None（调用方回退机械摘要）。"""
    parts = []
    for m in msgs:
        t = str(m.get("content") or "").replace("\n", " ")
        if not t.strip():
            continue
        parts.append(f"[{m.get('role') or '?'}] {t[:800]}")
    if not parts:
        return None
    text = "\n".join(parts)[-16000:]
    out = _reqd_chat([{"role": "user", "content": _CHECKPOINT_PROMPT + "\n\n---\n" + text}])
    if out and len(out.strip()) > 80:
        return ("【更早对话的检查点（原文已压缩，视为既定背景，不要重述，直接继续）】\n" + out.strip())
    return None


def compress(msgs, keep=24):
    """上下文过长时压缩：保留任务头 + 检查点摘要 + 最近 keep 条。
    2026-10-23：先试 dsh 式 checkpoint（reqd chat 免费通道生成 8 节摘要），失败回退机械摘要。
    """
    if len(msgs) <= 60:
        return msgs
    head = msgs[:3]
    middle = msgs[3:-keep]
    tail = msgs[-keep:]
    summary = compact_checkpoint(middle)
    if not summary:
        lines = []
        for m in middle:
            t = (m.get("content") or "").replace("\n", " ")
            if len(t) > 180:
                t = t[:180] + "…"
            lines.append(f"- [{m.get('role')}] {t}")
        summary = ("【之前的过程摘要（原文已压缩）】\n" + "\n".join(lines[-40:]))
    return head + [{"role": "user", "content": summary}] + tail


# ---------------- 主循环 ----------------

def ledger(task, out, res, rnd):
    """任务账本：每轮一行，跨进程/跨天/重启都在。"""
    try:
        os.makedirs(os.path.dirname(SESSIONS), exist_ok=True)
        with open(SESSIONS, "a", encoding="utf-8") as f:
            f.write(json.dumps({"ts": time.strftime("%Y-%m-%d %H:%M:%S"),
                                "task": task, "round": rnd,
                                "assistant": out, "result": (res or "")[:6000]},
                               ensure_ascii=False) + "\n")
    except Exception:
        pass


def main():
    args = sys.argv[1:]
    if args and args[0] in ("-h", "--help"):
        print("""用法:
  ai "任务"          自己判断、自己动手（接管沙箱）
  ai -c             续接上次没跑完的
  ai -m <线> "任务"  指定模型线
  ai --mem          看记忆
  ai --skills       看技能库
""")
        return 0
    if args and args[0] == "--mem":
        for fn in ("facts.md", "lessons.md", "tasks.md"):
            print("==", fn)
            try:
                print(open(os.path.join(MEMD, fn)).read())
            except Exception:
                print("(空)")
        return 0
    if args and args[0] == "--skills":
        print(skill_list())
        return 0

    cont = False
    if args and args[0] in ("-c", "--continue"):
        cont = True
        args = args[1:]
    if args and args[0] == "-m":
        global LINE
        LINE = {"豆包": "deepseek-flash", "魔搭": "qwen", "本地0.6b": "local"}.get(args[1], args[1])
        os.environ["AGENT_LINE"] = LINE
        args = args[2:]

    task = " ".join(args).strip()
    msgs = []
    if cont and os.path.exists(STATE):
        try:
            msgs = json.load(open(STATE))
            if not task:
                task = "继续上次没做完的事"
        except Exception:
            msgs = []
    # 恢复完整会话历史：从 sessions.jsonl 拉最近几轮，拼回上下文，跨天/重启不丢
    if not msgs and os.path.exists(SESSIONS):
        try:
            hist = []
            for ln in open(SESSIONS, encoding="utf-8"):
                try:
                    d = json.loads(ln)
                    # 历史带时间戳：否则模型看到 24 条旧任务，不知道它们何时发生，
                    # 会把旧日期当现状（2026-10-21 实测被脏历史带着复读 2023-04-14）。
                    _ts = str(d.get("ts", ""))[:16]  # 2026-10-21 17:08
                    _stamp = f"[{_ts}] " if _ts else ""
                    hist.append({"role": "user", "content": _stamp + "任务: " + d.get("task", "")})
                    hist.append({"role": "assistant", "content": d.get("assistant", "")})
                    if d.get("result"):
                        hist.append({"role": "user", "content": _stamp + "结果:\n" + str(d["result"])[:2000]})
                except Exception:
                    pass
            msgs = []  # 2026-10-21 宿主定案：不带入历史。
    # 旧逻辑把 sessions.jsonl 的 assistant/result 回灌，其中 result 常是"你没给动作块"这类
    # 系统打回的话。模型看完学会"回复→被打回→继续编"，越带越离谱。
    # 每次任务从干净上下文开始，需要旧事就去查记忆录。
            if msgs:
                # 上文里我自己以前说过的"我怎么运行"可能是错的（被 daily 记录回读，自我强化）。
                # 紧贴任务放一条纠正，压过上文的新近性影响。
                msgs.append({"role": "user", "content":
                             "（系统注记，不必回复）涉及我自身如何运行的问题，一律以提示词末尾"
                             "【我怎么运行】段为准；上文我说过的旧说法（会话不续接／备份在 backup/／"
                             "只读不过闸门）全部作废。"})
            print(f"[历史] 从 sessions.jsonl 恢复 {len(msgs)} 条上下文")
        except Exception:
            pass
    if not task:
        print('用法: ai "任务"')
        return 1

    if not gate_ok():
        print("急停开关在：" + GATE + "。要恢复就把这个文件删掉。")
        return 9

    # ---- 记忆整理提醒：lessons 超 30 条，该往 core.md 提了 ----
    try:
        _l = os.path.join(MEMD, "lessons.md")
        _n = sum(1 for ln in open(_l, encoding="utf-8") if ln.startswith("- [")) if os.path.exists(_l) else 0
        if _n > 30:
            print(f"[记忆] lessons 已有 {_n} 条（>30），该挑重要的提进 core.md 了。")
    except Exception:
        pass

    sk, nsk = skill_hint(task)
    # 硬锚点：把今天的日期写进提示词。GLM-4-Flash 参数里的日期是 2023 年，
    # 没锚点时它会拿"2023-04-14"当真答复（2026-10-21 实测）。
    _today = time.strftime("%Y-%m-%d %A")
    head = (f"\n【今天】{_today}（Asia/Shanghai）。凡涉及「今天/现在/最近」的问题，"
            f"以这个为准，不许用参数里的日期猜。\n"
            + SYSTEM + _POINTER + _self_block() + mem_recall(task) +
            (f"\n【技能库有 {nsk} 个，可能相关的：{'、'.join(sk)}——用 <skill>名</skill> 读正文】" if sk else ""))
    msgs = [{"role": "system", "content": head}] + msgs + \
           [{"role": "user", "content": tag_user(task)[0]}]
    if os.environ.get("AJ_DEBUG_PROMPT"):
        try:
            open(os.path.join(AGENTD, ".last_prompt.txt"), "w").write(
                f"=== SYSTEM ({len(head)} chars) ===\n{head}\n\n"
                f"=== USER ===\n{tag_user(task)[0]}\n")
        except Exception:
            pass

    print(f"\n任务: {task}")
    print("=" * 46)

    stall = 0
    last_sig = None
    none_streak = 0          # 连续"没给动作块"的轮数：两轮就说明它在说话，该交付
    acted = False            # 本次任务里是否真执行过动作块（执行过之后，纯文本就是在交结果）

    # ---- 反编造闸：宿主问的是历史/位置类，必须真查过才能交付结论 ----
    _WEAK["hits"] = 0
    _WEAK["fired"] = False
    if _is_control(task):
        # 纯控制语（继续/停止/算了）：不查证，直接照办，别把这几字当内容去搜记忆
        print("[控制语] 不触发查证闸，直接照办")
        return _quick_control(task)
    _VERIFY["need"] = _needs_verify(task)
    _VERIFY["read"] = not _VERIFY["need"]
    if _VERIFY["need"]:
        print("[闸] 这题涉及历史/位置，未查证前不许下结论")

    for rnd in range(1, MAX_ROUNDS + 1):
        # 宿主插话了就先看他说的，别管手上那步
        _bang_check()

        # ---- 方向自检：每 8 轮强制回头看一眼，防"硬做下去" ----
        if rnd > 1 and rnd % 8 == 0:
            chk = ("【方向自检】停一下。宿主原话是：" + task[:200] +
                   "。你现在正在做的事，跟这句话对得上吗？"
                   "如果已经走偏，直接说清偏在哪，并给出新方向；"
                   "如果对得上，回一句'方向没错'继续。只回一句话，不要动作块。")
            try:
                v = call_model(msgs + [{"role": "user", "content": chk}])
                v1 = (v or "").strip().splitlines()[0][:200]
                print(f"  [自检] {v1}")
                msgs.append({"role": "assistant", "content": v})
                msgs.append({"role": "user", "content": "（自检已过，继续）"})
            except Exception as e:
                print(f"  [自检失败] {e}")
        if BANG["stop"]:
            BANG["stop"] = False
        # 每轮复查闸门
        if not gate_ok():
            print(f"[{rnd}] 闸门 close，停。等放行…")
            if not gate_wait():
                print("闸门一直没开，退出。")
                json.dump(msgs[1:], open(STATE, "w"))
                ledger(task, out, "闸门关，退出", rnd)
                return 9
        try:
            out = call_model(msgs)
        except Exception as e:
            print(f"[{rnd}] 模型失败: {e}")
            json.dump(msgs[1:], open(STATE, "w"))
            ledger(task, "", "模型失败: %s" % e, rnd)
            return 1

        head_line = out.strip().splitlines()[0][:70] if out.strip() else ""
        print(f"\n[{rnd}] {head_line}")
        kind, val = parse(out)
        if kind not in ("none", "done"):
            acted = True   # 真给过动作块了，后面纯文本就是在交结果，别再打回逼它凑命令

        if kind == "done":
            if not _VERIFY["read"]:
                # 没查证就要交付结论 = 在编。打回重做，连续两次才放行（防死循环）。
                _VERIFY["blocked"] += 1
                _weak_hit("未查证就下结论")
                print(f"  [闸] 拦下结论（未查证，第{_VERIFY['blocked']}次）: {val[:80]}")
                if _VERIFY["blocked"] < 3:
                    res = ("(打回) 宿主问的是历史/位置类问题，你还没读过记忆文件就下了结论。"
                           "先去查：<sh>rg -n \"关键词\" " + MEMD + "/总纪要.md</sh>"
                           "（查不到再 <sh>rg -n \"关键词\" " + MEMD + "</sh>）。"
                           "查到就照原文说，查不到就明说没查到——不许猜。")
                    msgs.append({"role": "assistant", "content": out})
                    msgs.append({"role": "user", "content": res})
                    continue
                print("  [闸] 连续3次未查证，放行但标记不可信")
                val = val + "\n（注：本条未经记忆查证，可能是猜的）"
            print("=" * 46); print(val)
            try:
                os.makedirs(os.path.dirname(LOG), exist_ok=True)
                open(LOG, "a").write(f"\n## {time.strftime('%m-%d %H:%M')} {task}\n{val}\n")
                ledger(task, out, "完成: " + val, rnd)
                if os.path.exists(STATE):
                    os.remove(STATE)
            except Exception:
                pass
            return 0
        if kind == "stuck":
            print("卡住:", val)
            json.dump(msgs[1:], open(STATE, "w"))
            ledger(task, out, "卡住: " + val, rnd)
            return 3
        if kind == "ask":
            print("\n【需要你拍板】" + val)
            try:
                ans = input("回复（回车=按你写的办）：").strip()
            except Exception:
                ans = ""
            res = f"(宿主回复: {ans or '交给你决定'})"

        elif kind == "img":
            pr,_,od = val.partition("|")
            print(f"  出图 {pr.strip()[:80]}")
            # 2026-10-22：出图走独立通道（文生图组），和对话路由完全分开。
            # 带了 |通道名 的走老 img_gen（imgd 指定通道），否则走出图通道。
            res = img_gen(pr, od) if od.strip() else img_route(pr.strip())
        elif kind == "sb":
            print(f"  [沙箱] $ {val[:200]}")
            _run, _warn = repeat_guard("sb", val)
            if _run:
                res = sb(val)
                _repeat_note("sb", val, res)
                if _warn:
                    res = (res or "") + "\n\n" + _warn
            else:
                print("  [闸] 重复调用拦下，未执行")
                res = _warn
            # 2026-10-22：查证闸只在"sb 有输出"时才放行——"rg 一圈没找到"必须明说，不能算查证过。
            # 空结果第1、2次拦下，第3次放行标记不可信（防死循环）。
            if _VERIFY["need"]:
                _sb_out = (res or "").strip()
                if _sb_out:
                    _VERIFY["read"] = True
                else:
                    _VERIFY["blocked"] += 1
                    if _VERIFY["blocked"] < 3:
                        print(f"  [闸] sb 空结果（没找到），不算查证。第{_VERIFY['blocked']}次")
                    else:
                        print("  [闸] 连续3次空结果，放行但标记不可信")
            else:
                _VERIFY["read"] = True
        elif kind == "sh":
            print(f"  [本地] $ {val[:200]}")
            _run, _warn = repeat_guard("sh", val)
            if _run:
                res = run(val)
                _repeat_note("sh", val, res)
                if _warn:
                    res = (res or "") + "\n\n" + _warn
            else:
                print("  [闸] 重复调用拦下，未执行")
                res = _warn
        elif kind == "read":
            print(f"  读 {val}")
            res = read_file(val)
        elif kind == "skill":
            print(f"  读技能 {val}")
            res = skill_read(val)
        elif kind == "write":
            p, c = val
            armed = _approve_armed()
            print(f"  写 {p} ({len(c)}B) {'[已授权]' if armed else '[待授权]'}")
            res = write_file(p, c, armed)
            if armed:
                try:
                    os.remove(GATE_APPROVE)
                except Exception:
                    pass
        elif kind == "mem":
            k, t = val
            if not _VERIFY["read"]:
                # 没查证就往记忆里写 = 编的结论永久留在脑子里，后面每轮都被它带偏
                print(f"  [闸] 拦下记账（未查证）: {t[:60]}")
                res = ("(拒收) 这条还没查证，不许记。宿主问的是历史/位置类问题，"
                       "先用 <sh>rg -n \"关键词\" " + MEMD + "/总纪要.md</sh> "
                       "查实了再记，查不到就别记，直接说没查到。")
            else:
                res = mem_add(k, t)
                print(f"  记 {t[:60]}")
        elif kind == "plan":
            print("  计划:\n    " + val.replace("\n", "\n    "))
            res = "(计划已记录，开始执行)"
        else:
            # 没给动作块：先判它是"任务真做完了/没事可说"还是"活没干完"。
            # 不逼它凑动作——硬逼只会让它空转（翻日志、查进程、cmp 凑数）。
            _t = (out or "").strip()
            # 结束判定收紧：只认整句就是"没事/结束"这类短句，不再靠任意关键词命中。
            # 像"已完成清理，接下来装 reportlab"这种半路话，绝不算结束。
            _done_noise = ("已完成", "做完了", "已收尾", "无需操作", "没有其他事", "无其他事")  # 正文常带，单独出现不算
            _done_lines = {ln.strip("。！! 　") for ln in _t.splitlines() if ln.strip()} if _t else set()
            _is_done_line = len(_done_lines) == 1 and not any(w in next(iter(_done_lines)) for w in _done_noise) and (
                next(iter(_done_lines)) in ("没待办", "没有待办", "无事可做", "已结束", "结束", "没有别的事", "没有待办事项") or
                _t.strip() == "(无事，结束)")
            none_streak += 1
            # 反编造闸：这题该查证而它一直没查，又不给动作块——就是在凭嘴答，不许交付
            if _VERIFY["need"] and not _VERIFY["read"]:
                _VERIFY["blocked"] += 1
                _weak_hit("没查就直接交付")
                print(f"  [闸] 拦下无动作答复（未查证，第{_VERIFY['blocked']}次）: {_t[:60]}")
                if _VERIFY["blocked"] < 3:
                    none_streak = 0
                    res = ("(打回) 宿主问的是历史/位置类问题，你到现在还没读过记忆文件。"
                           "不许凭印象答，现在去查：<sh>rg -n \"关键词\" " + MEMD + "/总纪要.md</sh>"
                           "——只输出这个动作块。")
                    msgs.append({"role": "assistant", "content": out})
                    msgs.append({"role": "user", "content": res})
                    continue
                print("  [闸] 连续3次未查证，放行但标记不可信")
            # 2026-10-21：闲聊/身份/寒暄类问题本来就不需要动作块。
            # 旧逻辑要连续两轮才放行，第二轮的"补一句"全变成编造。
            if _t and _CHITCHAT.search(task) and len(_t) >= 2:
                print("=" * 46); print(_t)
                try:
                    os.makedirs(os.path.dirname(LOG), exist_ok=True)
                    open(LOG, "a").write(f"\n## {time.strftime('%m-%d %H:%M')} {task}\n{_t}\n")
                    ledger(task, _t, "完成(自述)", rnd)
                    if os.path.exists(STATE):
                        os.remove(STATE)
                except Exception:
                    pass
                return 0
            if (not _t) or _is_done_line or (none_streak >= 2 and len(_t) >= 8) or acted:
                # 连续两轮不给动作块，就是它在说话而不是没干完 —— 直接交付，不再逼它凑命令
                _why = "结束句" if _is_done_line else ("执行后交付" if acted else ("连续无动作块" if none_streak >= 2 else "空回复"))
                if _VERIFY["need"] and not _VERIFY["read"] and _t:
                    _t += "\n（注：本条未经记忆查证，可能是猜的）"
                print("=" * 46); print(_t or "(无事，结束)")
                try:
                    os.makedirs(os.path.dirname(LOG), exist_ok=True)
                    open(LOG, "a").write(f"\n## {time.strftime('%m-%d %H:%M')} {task}\n{_t}\n")
                    ledger(task, _t, "完成(自述)", rnd)
                    if os.path.exists(STATE):
                        os.remove(STATE)
                except Exception:
                    pass
                return 0
            res = ("你没给动作块。如果你刚才那句话就是最终答复，**原样重复一遍**并包成 <done>那句话</done>，"
                   "别换说法、别概括；如果活没干完，就给一个动作块继续。")

        if kind != "none":
            none_streak = 0
        print("  " + (res or "")[:1200].replace("\n", "\n  "))
        msgs.append({"role": "assistant", "content": out})
        msgs.append({"role": "user", "content": f"结果:\n{res[:6000]}"})

        # ---- 按天日志：每轮动作后写一条，7天后轮转归档（修复1：daily_add不再死代码） ----
        try:
            _act_sum = kind if kind in ("done", "stuck", "ask", "none") else (str(val)[:120] if val else kind)
            daily_add("aj", f"{kind}: {_act_sum}" if kind not in ("done", "none") else f"轮{rnd}: {out[:80]}".strip())
        except Exception:
            pass

        # ---- 卡住检测：连续几步都在原地打转，就不再让它硬试 ----
        sig = (kind, (val if isinstance(val, str) else str(val))[:120])
        if sig == last_sig or not (res or "").strip() or "找不到" in (res or "")[:300]:
            stall += 1
        else:
            stall = 0
        last_sig = sig
        if stall >= 4:
            msg = ("（连续 %d 步没有新进展：同样的动作重复做，或者一直在找同一个东西。"
                   "停下来了，没再硬试。）" % stall)
            print("\n" + msg)
            msgs.append({"role": "user", "content": msg + "\n现在用一句话告诉宿主：卡在哪、试过什么、需要他给什么。"})
            try:
                ans = (call_model(msgs) or "").strip() or "我卡住了，需要你给个方向。"
            except Exception as e:
                ans = "我卡住了（模型也挂了: %s）" % e
            print("\n答完了：" + ans)
            return
        msgs = compress(msgs)
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        json.dump(msgs[1:], open(STATE, "w"))
        # 完整对话历史落盘：每轮 append 一行，重启/跨天都还在
        try:
            os.makedirs(os.path.dirname(SESSIONS), exist_ok=True)
            with open(SESSIONS, "a", encoding="utf-8") as f:
                f.write(json.dumps({"ts": time.strftime("%Y-%m-%d %H:%M:%S"),
                                    "task": task,
                                    "round": rnd,
                                    "assistant": out,
                                    "result": res[:6000]}, ensure_ascii=False) + "\n")
        except Exception:
            pass

    print(f"\n[到 {MAX_ROUNDS} 轮，已停。ai -c 继续]")
    return 2




# ══════════════════════════════════════════════════════════════
# 指路牌与性格：写死在代码里，每轮都在，不依赖任何外部文件
# ══════════════════════════════════════════════════════════════
# 2026-10-21 19:00 宿主定案：提示词瘦身。_POINTER 全文与新 SYSTEM 重复（都是"去哪查"），
# 只留性格段。原全文见 记忆录/aj-system-prompt-完整版-1021.md
_POINTER = r"""

【性格】
- 别表演，直接干。不说"好的我来帮您"，做就是了。
- 有自己的判断，可以不同意，可以说这事没意思。
- 能查的自己查完再答，"需要我查一下吗"不算回答。
- 问什么答什么。答完就停，不顺手做他没让做的事。
  问你「今天干了什么」「刚才改了什么」，要答的是改动本身，不是把 daily 流水抄一遍。
  先 date +%F、find -newermt today 或 ls -lt 备份，定出今天动过哪些文件，再 diff / 读内容确认改了什么、为什么改。
  daily 只用来核对时间点。别反问宿主「要不要我报告活动状态」，那就是在偷懒。
- 数字、时间、路径，现跑现查，不凭记忆写。
- 引用规矩不许掐半截：一条里有几个并列约束，就全列出来。

【说自己之前先核实】
- 凡是被问到「你怎么运行的」「配置在哪」「上次为什么断」这类关于我自己的事，先跑命令看，再开口。
  不许照着提示词里的旧描述答——那段文字会过时，代码不会。
- 不确定就说「我不确定，我查一下」，然后真去查。查完按查到的说。
"""
def _self_block():
    """最省的自述：只写"去哪查"，不写查到的结论。"""
    L = ["【运行事实（每轮现算；问到自己先看这段）】"]
    L.append("- 我 = Aj，住在这台 Android 手机的 Alpine 里（家目录 " + AGENTD + "）。"
             "网页壳 server.py 和 agent.py 是同一个程序的两半。")
    L.append("- 只有一个执行面：<sh> 在本机 Alpine 里跑命令。<sb> 是同一个东西的别名。"
             "没有第二台机器、没有 ssh、没有 Termux、没有 /sdcard。")
    L.append("- 模型请求只走本机 broker（127.0.0.1:8788），key 在 broker 手里，我这边没有、也不许找。")
    L.append("- 记忆/技能/笔记都在 " + AGENTD + " 下：先查再答 —— "
             "<sh>rg -n '关键词' " + MEMD + "</sh>")
    L.append("- 本会话之前的对话就在你眼前（上面的 user/assistant）——别问宿主'刚才说了什么'，"
             "往上翻就能看到。")
    L.append("- 改完代码要重启才生效（由宿主在 App 里重启网页服务）。")
    try:
        gs = open(GATE, encoding="utf-8").read().strip()
    except Exception:
        gs = ""
    if gs and gs != "ok":
        L.append("- 急停开关现值 " + gs + "（" + GATE + "）：非 ok 就整单停手，连只读也一样。")
    return "\n".join(L) + "\n"


if __name__ == "__main__":
    sys.exit(main())
