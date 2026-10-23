package dev.aj.miniapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 极简 MCP 客户端：stdio + JSON-RPC 2.0。
 *
 * 配置放 filesDir/mcp/servers.json：
 *   [{"name":"filesystem","command":"npx -y @modelcontextprotocol/server-filesystem /host"}]
 *
 * 服务器进程跑在 proot 里（跟聊天命令同一个 Linux），所以 npx/python 都能用。
 * 工具名对外暴露成 mcp__<server>__<tool>，模型拿到的就是普通 function。
 */
class McpManager(private val ctx: Context) {

    data class Server(val name: String, val command: String, val enabled: Boolean = true)

    private val dir: File get() = File(ctx.filesDir, "mcp").apply { mkdirs() }
    private val conf: File get() = File(dir, "servers.json")
    private val cache = mutableMapOf<String, Conn>()

    /** 内置一个示例服务器，方便验证链路：python3 /host/mcp/demo.py */
    init {
        runCatching {
            val demo = File(dir, "demo.py")
            if (!demo.exists()) demo.writeText(DEMO_MCP)
        }
    }

    fun servers(): MutableList<Server> {
        val out = mutableListOf<Server>()
        if (!conf.exists()) return out
        runCatching {
            val arr = JSONArray(conf.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Server(
                    o.optString("name", "s$i"),
                    o.optString("command", ""),
                    o.optBoolean("enabled", true)
                )
            }
        }
        return out
    }

    fun save(list: List<Server>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("command", s.command)
                put("enabled", s.enabled)
            })
        }
        conf.writeText(arr.toString())
    }

    fun add(s: Server) {
        val list = servers().filter { it.name != s.name }.toMutableList()
        list.add(s)
        save(list)
    }

    fun remove(name: String) {
        runCatching { cache.remove(name)?.proc?.destroy() }
        save(servers().filter { it.name != name })
    }

    fun setEnabled(name: String, on: Boolean) {
        if (!on) runCatching { cache.remove(name)?.proc?.destroy() }
        val list = servers().map { if (it.name == name) it.copy(enabled = on) else it }
        save(list)
    }

    /** 只测一个服务能不能连上、有多少工具 */
    fun probe(name: String): String {
        val s = servers().firstOrNull { it.name == name } ?: return "没有这个服务"
        stopAll()
        val conn = start(s) ?: return "连接失败：起不来或 initialize 没响应"
        val ts = conn.tools()
        return if (ts.isEmpty()) "连上了，但没有工具" else "连上了，工具 ${ts.size} 个：" +
            ts.joinToString("、") { it.optString("name", "?") }
    }

    fun stopAll() {
        cache.values.forEach { runCatching { it.proc.destroy() } }
        cache.clear()
    }

    /** 拉齐所有服务器的工具，返回可直接塞进 tools 的 JSON */
    fun toolDefs(): JSONArray {
        val arr = JSONArray()
        map.clear()
        for (s in servers()) {
            if (!s.enabled) continue
            val conn = start(s) ?: continue
            for (t in conn.tools()) {
                val fn = t.optString("name", "")
                if (fn.isBlank()) continue
                val schema = t.optJSONObject("inputSchema") ?: JSONObject().apply { put("type", "object") }
                // 火山/豆包这类接口只认 ^[a-zA-Z0-9_-]{1,64}$，中文名、点号都会被 400 掉，转义成别名再登记回来
                var alias = "mcp__${safe(s.name)}__${safe(fn)}"
                if (alias.length > 64) alias = alias.take(64)
                var uniq = alias
                var k = 2
                while (map.containsKey(uniq)) {
                    uniq = alias.take(60) + "_" + k
                    k++
                }
                map[uniq] = s.name to fn
                arr.put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", uniq)
                        put("description", t.optString("description", "MCP $fn"))
                        put("parameters", schema)
                    })
                })
            }
        }
        return arr
    }

    /** 别名表：给模型的干净名字 → 真实 服务器名 + 工具名 */
    private val map = mutableMapOf<String, Pair<String, String>>()

    /** 只留 ASCII 字母数字和 _-，其余换下划线 */
    private fun safe(s: String): String = buildString {
        for (c in s) {
            append(if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '-') c else '_')
        }
    }

    fun isMcp(name: String) = name.startsWith("mcp__")

    /** 调一个 MCP 工具，返回给模型看的文本 */
    fun call(fullName: String, argsJson: String): String {
        val pair = map[fullName]
        val serverName: String
        val toolName: String
        if (pair != null) {
            serverName = pair.first
            toolName = pair.second
        } else {
            // 别名表没建起来（进程重启后直接调）就退回按名字拆
            val rest = fullName.removePrefix("mcp__")
            val sep = rest.indexOf("__")
            if (sep <= 0) return "[MCP 错误] 工具名不对: $fullName"
            serverName = rest.substring(0, sep)
            toolName = rest.substring(sep + 2)
        }
        val s = servers().firstOrNull { it.name == serverName }
            ?: return "[MCP 错误] 没找到服务器 $serverName"
        val conn = start(s) ?: return "[MCP 错误] $serverName 起不来（检查 command 和依赖）"
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val r = conn.rpc("tools/call", JSONObject().apply {
            put("name", toolName)
            put("arguments", args)
        }) ?: return "[MCP 错误] $serverName 没在 60 秒内回应"

        val err = r.optJSONObject("error")
        if (err != null) return "[MCP 错误] " + err.optString("message", err.toString())
        val res = r.optJSONObject("result") ?: return r.toString().take(2000)

        val sb = StringBuilder()
        val content = res.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val c = content.getJSONObject(i)
                when (c.optString("type")) {
                    "text" -> sb.append(c.optString("text", "")).append('\n')
                    else -> sb.append('[').append(c.optString("type", "?")).append("]\n")
                }
            }
        }
        if (sb.isBlank()) sb.append(res.toString())
        return sb.toString().trim().take(20000)
    }

    private fun start(s: Server): Conn? {
        if (s.command.isBlank()) return null
        cache[s.name]?.let { if (it.proc.isAlive) return it else cache.remove(s.name) }
        val proc = runCatching { Linux.startInteractive(ctx, "exec " + s.command) }.getOrNull() ?: return null
        val c = Conn(s.name, proc)
        val hi = c.rpc("initialize", JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "aj-mini")
                put("version", "1.0")
            })
        })
        if (hi == null) {
            runCatching { proc.destroy() }
            return null
        }
        c.notify("notifications/initialized")
        cache[s.name] = c
        return c
    }

    class Conn(val name: String, val proc: Process) {
        private val out = proc.outputStream
        private val queue = LinkedBlockingQueue<String>()
        private var seq = 0

        init {
            thread(isDaemon = true) {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { queue.put(it) }
                }
            }
            thread(isDaemon = true) {
                runCatching { proc.errorStream.bufferedReader().forEachLine { } }
            }
        }

        @Synchronized
        fun rpc(method: String, params: JSONObject?): JSONObject? {
            val id = ++seq
            val req = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            }
            runCatching {
                out.write((req.toString() + "\n").toByteArray())
                out.flush()
            }.onFailure { return null }

            val deadline = System.currentTimeMillis() + 60_000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val line = queue.poll(left, TimeUnit.MILLISECONDS) ?: return null
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (o.optInt("id", -1) == id) return o
            }
        }

        fun notify(method: String) {
            runCatching {
                out.write((JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", method)
                }.toString() + "\n").toByteArray())
                out.flush()
            }
        }

        fun tools(): List<JSONObject> {
            val r = rpc("tools/list", JSONObject()) ?: return emptyList()
            val arr = r.optJSONObject("result")?.optJSONArray("tools") ?: return emptyList()
            val out = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            return out
        }
    }
}
