package dev.aj.miniapp

/** 内置示例 MCP 服务器（stdio JSON-RPC），提供一个 now 工具 */
internal const val DEMO_MCP = """
import json, sys, datetime


def send(o):
    sys.stdout.write(json.dumps(o) + "\n")
    sys.stdout.flush()


TOOLS = [{
    "name": "now",
    "description": "返回当前日期时间",
    "inputSchema": {"type": "object", "properties": {}}
}]

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        msg = json.loads(line)
    except Exception:
        continue
    method = msg.get("method", "")
    mid = msg.get("id")
    if mid is None:
        continue
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "demo", "version": "1.0"}}})
    elif method == "tools/list":
        send({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
    elif method == "tools/call":
        now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        send({"jsonrpc": "2.0", "id": mid, "result": {
            "content": [{"type": "text", "text": now}], "isError": False}})
    else:
        send({"jsonrpc": "2.0", "id": mid,
              "error": {"code": -32601, "message": "unknown method"}})
"""
