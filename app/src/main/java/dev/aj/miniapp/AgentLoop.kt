package dev.aj.miniapp

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 工具循环：模型要命令 → 我们执行 → 结果回传 → 继续，直到模型给出最终回答。
 *
 * 这是 Minis 的核心，也是「我掌控」的关键：
 *  - 每条命令执行前可被钩子拦截
 *  - 钩子放 /host/hooks/before.sh，返回非 0 就跳过这条命令
 */
class AgentLoop(
    private val ctx: Context,
    private val settings: Settings,
    private val onEvent: (Event) -> Unit
) {

    sealed class Event {
        class Thinking(val text: String) : Event()
        class Command(val cmd: String) : Event()
        class Output(val text: String) : Event()
        class Answer(val text: String) : Event()
        class Error(val msg: String) : Event()
    }

    private val client = LlmClient(settings)

    private val envStore by lazy { EnvStore(ctx) }
    private val skills by lazy { SkillsStore(ctx) }
    private val memory by lazy { MemoryStore(ctx) }

    /** 系统提示 = 基础人格 + 记忆 + 命中技能的正文（每轮重算，记忆和技能随时生效） */
    private fun buildSystem(userText: String): String {
        val sb = StringBuilder(settings.systemPrompt.trim())
        sb.append("\n\n## 产物输出\n")
        sb.append("图片、图表、视频、文档等产物一律写进 /out 目录（例如 /out/chart.png）。")
        sb.append("写完后只回一句说明路径，不要把 base64 或 ANSI 彩色字符贴进对话——App 会自动在聊天里显示 /out 里的新文件。\n")
        val mem = memory.recent()
        if (mem.isNotBlank()) {
            sb.append("\n\n## 记忆（之前的对话，供参考）\n").append(mem)
        }
        val hit = skills.match(userText)
        if (hit.isNotEmpty()) {
            sb.append("\n\n## 技能（命中，照里面的步骤做）\n")
            for ((n, body) in hit) {
                sb.append("\n### ").append(n).append('\n').append(body).append('\n')
            }
        }
        return sb.toString()
    }

    /** 会话历史，只保留在内存，不落盘（第一版） */
    private val history = mutableListOf<JSONObject>()

    fun reset() = history.clear()

    /** 会话文件：只存对话，不存 system（system 每轮重建） */
    private val sessionFile: java.io.File
        get() = java.io.File(ctx.filesDir, "sessions/last.json").apply { parentFile?.mkdirs() }

    fun save() {
        runCatching {
            val arr = org.json.JSONArray()
            history.filter { it.optString("role") != "system" }.forEach { arr.put(it) }
            sessionFile.writeText(arr.toString())
        }
    }

    /** 启动时把上次会话读回来 */
    fun load(): Int {
        val f = sessionFile
        if (!f.exists()) return 0
        return runCatching {
            val arr = org.json.JSONArray(f.readText())
            history.clear()
            for (i in 0 until arr.length()) history += arr.getJSONObject(i)
            history.size
        }.getOrDefault(0)
    }

    fun send(userText: String, parts: List<JSONObject> = emptyList()) {
        try {
            turn(userText, parts)
        } finally {
            save()
        }
    }

    private fun turn(userText: String, parts: List<JSONObject>) {
        history += JSONObject().apply {
            put("role", "user")
            if (parts.isEmpty()) {
                put("content", userText)
            } else {
                val arr = org.json.JSONArray()
                arr.put(JSONObject().apply {
                    put("type", "text")
                    put("text", userText.ifBlank { "(见附件)" })
                })
                parts.forEach { arr.put(it) }
                put("content", arr)
            }
        }

        // 每轮重建 system（记忆/技能会变），旧的丢掉
        history.removeAll { it.optString("role") == "system" }
        history.add(0, JSONObject().apply {
            put("role", "system")
            put("content", buildSystem(userText))
        })

        var turn = 0
        while (turn < settings.maxTurns) {
            turn++
            val reply = client.chat(history)

            if (reply.error != null) {
                onEvent(Event.Error(reply.error!!))
                return
            }

            // 模型要执行命令
            if (reply.hasTool) {
                reply.text?.takeIf { it.isNotBlank() }?.let { onEvent(Event.Thinking(it)) }

                // 把 assistant 的 tool_calls 原样写回历史
                val assistantMsg = JSONObject().apply {
                    put("role", "assistant")
                    put("content", reply.text ?: JSONObject.NULL)
                    put("tool_calls", org.json.JSONArray().apply {
                        reply.toolCalls.forEach { tc ->
                            put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                })
                            })
                        }
                    })
                }
                history += assistantMsg

                // 逐条执行
                for (tc in reply.toolCalls) {
                    val cmd = runCatching {
                        JSONObject(tc.arguments).optString("command", "")
                    }.getOrDefault("")

                    val result = if (cmd.isBlank()) {
                        "[参数错误] 没有 command"
                    } else {
                        onEvent(Event.Command(cmd))
                        val blocked = runBeforeHook(cmd)
                        val out = if (blocked != null) {
                            blocked
                        } else {
                            val (code, o) = Linux.run(ctx, cmd, 120, envStore.asEnv())
                            onEvent(Event.Output(o))
                            runAfterHook(cmd, code, o)
                            if (code == 0) o else "$o\n[exit=$code]"
                        }
                        out
                    }

                    history += JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", tc.id)
                        put("content", result.take(20000))
                    }
                }
                continue
            }

            // 最终回答
            val ans = reply.text ?: "(空回复)"
            onEvent(Event.Answer(ans))
            runCatching { memory.append(userText, ans) }
            history += JSONObject().apply {
                put("role", "assistant")
                put("content", reply.text ?: "")
            }
            return
        }

        onEvent(Event.Error("超过 ${settings.maxTurns} 轮还没结束，停手了。可能模型在死循环。"))
    }

    // ---------------------------------------------------------------- 钩子

    private fun hooksDir(): File = File(ctx.filesDir, "hooks")

    /** 返回非 null 表示被拦截，内容是给模型的回执 */
    private fun runBeforeHook(cmd: String): String? {
        val f = File(hooksDir(), "before.sh")
        if (!f.exists()) return null
        // 用绝对路径调，免得 PATH 不对时连 sh 都找不到；钩子自身跑不起来就跳过，不拦命令
        val (code, out) = Linux.run(ctx, "/bin/sh /host/hooks/before.sh " + shellQuote(cmd), 30, envStore.asEnv())
        if (code == 127 || out.contains("not found")) return null
        return if (code == 0) null else "[钩子拦截 exit=$code] $out"
    }

    private fun runAfterHook(cmd: String, exit: Int, output: String) {
        val f = File(hooksDir(), "after.sh")
        if (!f.exists()) return
        Linux.run(
            ctx,
            "/bin/sh /host/hooks/after.sh ${shellQuote(cmd)} $exit ${shellQuote(output.take(2000))}",
            30,
            envStore.asEnv()
        )
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** 初始化目录结构：hooks / skills / memory 都建好，你往里丢文件就生效 */
    fun ensureDirs() {
        for (d in listOf("hooks", "skills", "memory")) File(ctx.filesDir, d).mkdirs()

        val before = File(hooksDir(), "before.sh")
        if (!before.exists()) {
            before.writeText(
                """#!/bin/sh
# 每条命令执行前调用，$1 = 要执行的命令
# 返回 0 = 放行；返回非 0 = 拦截，输出会回给模型
# 例：禁止 rm -rf
case "${'$'}1" in
  *"rm -rf /"*) echo "危险命令，已拦截"; exit 1 ;;
esac
exit 0
"""
            )
        }

        val after = File(hooksDir(), "after.sh")
        if (!after.exists()) {
            after.writeText(
                """#!/bin/sh
# 每条命令执行后调用
# $1 = 命令  $2 = 退出码  $3 = 输出片段
exit 0
"""
            )
        }

        val global = File(File(ctx.filesDir, "memory"), "GLOBAL.md")
        if (!global.exists()) {
            global.writeText("""# 全局记忆

这里写的东西会被读进上下文。你自己改，随时生效。
""")
        }
    }
}
