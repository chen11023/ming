package dev.aj.miniapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥保险箱：Android Keystore 里生成 AES-256 密钥（私钥不出 TEE），
 * 数据用 AES/GCM 加密后 base64 存 SharedPreferences。
 * 磁盘上没有任何明文，就算把 App 私有目录整个拷走也解不开（密钥绑这台设备）。
 */
object Vault {

    private const val ALIAS = "aj_vault_v1"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val exist = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (exist != null) return exist.secretKey

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Throwable) {
            ""
        }
    }

    fun decrypt(blob: String): String {
        if (blob.isEmpty()) return ""
        // 老版本存的明文，原样返回（第一次保存时会自动转成密文）
        if (!blob.startsWith(PREFIX)) return blob
        return try {
            val all = Base64.decode(blob.substring(PREFIX.length), Base64.NO_WRAP)
            if (all.size < 13) return ""
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
        } catch (e: Throwable) {
            ""
        }
    }

    fun isEncrypted(v: String) = v.startsWith(PREFIX)
}

/**
 * 环境变量 / 密钥表。整体加密后存一个字符串，不逐条落盘。
 * 注入方式：起终端会话时塞进 TerminalSession 的 env 数组，
 * 聊天里跑命令时塞进 ProcessBuilder 的环境 —— 只活在进程内存里，不写任何文件。
 */
class EnvStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("aj_env", Context.MODE_PRIVATE)

    fun all(): List<Pair<String, String>> {
        val raw = Vault.decrypt(sp.getString("blob", "") ?: "")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("k", "")
                if (k.isBlank()) null else k to o.optString("v", "")
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun save(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.filter { it.first.isNotBlank() }.forEach { p ->
            arr.put(JSONObject().apply {
                put("k", p.first)
                put("v", p.second)
            })
        }
        sp.edit().putString("blob", Vault.encrypt(arr.toString())).apply()
    }

    fun put(k: String, v: String) {
        val cur = all().toMutableList()
        val i = cur.indexOfFirst { it.first == k }
        if (i >= 0) cur[i] = k to v else cur += k to v
        save(cur)
    }

    fun remove(k: String) = save(all().filter { it.first != k })

    /** 注入环境用；键名不合法的跳过（不能带 = 和空格） */
    fun asEnv(): Map<String, String> = all()
        .filter { it.first.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
        .toMap()
}

/**
 * 技能目录：filesDir/skills/<名字>/SKILL.md
 * 命中规则和 Minis 一个思路：名字或描述里的词出现在用户这句话里，就把正文拼进系统提示。
 */
class SkillsStore(private val ctx: Context) {

    fun dir(): File = File(ctx.filesDir, "skills").apply { mkdirs() }

    fun names(): List<String> = dir().listFiles()
        ?.filter { it.isDirectory && File(it, "SKILL.md").exists() }
        ?.map { it.name }?.sorted().orEmpty()

    fun read(name: String): String {
        val f = File(File(dir(), name), "SKILL.md")
        return if (f.exists()) f.readText() else ""
    }

    fun save(name: String, body: String) {
        val d = File(dir(), sanitize(name))
        d.mkdirs()
        File(d, "SKILL.md").writeText(body)
    }

    fun delete(name: String) {
        val d = File(dir(), sanitize(name))
        // 只删技能目录里的 SKILL.md 和空目录，不做递归删除
        File(d, "SKILL.md").delete()
        d.delete()
    }

    private fun sanitize(name: String) = name.trim()
        .replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5_-]"), "-")
        .take(40).ifBlank { "skill" }

    private fun meta(name: String, body: String): String {
        // frontmatter 的 description 和 name 一起参与匹配
        val fm = if (body.startsWith("---")) {
            val end = body.indexOf("\n---", 3)
            if (end > 0) body.substring(3, end) else ""
        } else ""
        val desc = Regex("(?m)^description:\\s*(.+)$").find(fm)?.groupValues?.get(1).orEmpty()
        return "$name $desc"
    }

    private fun tokens(s: String): List<String> =
        Regex("[A-Za-z][A-Za-z0-9_+.-]{1,}").findAll(s).map { it.value.lowercase() }.toList()

    /** 返回命中的技能（名字 + 正文），最多 max 个 */
    fun match(task: String, max: Int = 2, maxChars: Int = 2000): List<Pair<String, String>> {
        val t = task.lowercase()
        val out = ArrayList<Pair<String, String>>()
        for (n in names()) {
            val body = read(n)
            val meta = meta(n, body)
            val hit = n.lowercase().let { nm -> nm.length >= 2 && t.contains(nm) } ||
                tokens(meta).any { it.length >= 3 && t.contains(it) } ||
                cjkHit(t, meta)
            if (!hit) continue
            out += n to body.take(maxChars)
            if (out.size >= max) break
        }
        return out
    }

    /** 中文按 2-gram 命中，避免"画图"匹配不到"画一张图"这类长句 */
    private fun cjkHit(task: String, meta: String): Boolean {
        val words = Regex("[\\u4e00-\\u9fa5]{2,}").findAll(meta).map { it.value }.toList()
        for (w in words) {
            if (task.contains(w)) return true
            var i = 0
            while (i + 2 <= w.length) {
                if (task.contains(w.substring(i, i + 2))) return true
                i++
            }
        }
        return false
    }
}

/**
 * 记忆：filesDir/memory/GLOBAL.md（长期）+ YYYY-MM-DD.md（当天）。
 * 新会话把 GLOBAL.md 和今天/昨天两份日志拼进系统提示，和 Minis 的注入范围一致。
 */
class MemoryStore(private val ctx: Context) {

    fun dir(): File = File(ctx.filesDir, "memory").apply { mkdirs() }

    fun global(): File = File(dir(), "GLOBAL.md")

    fun ensure(): File {
        val g = global()
        if (!g.exists()) g.writeText("# 全局记忆\n\n这里写的东西每次对话都会读进上下文。\n")
        return g
    }

    private fun dayFile(stamp: String): File = File(dir(), "$stamp.md")

    fun append(userText: String, answer: String) {
        ensure()
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        val stamp = now.substring(0, 10)
        val f = dayFile(stamp)
        val entry = buildString {
            append("## ").append(now.substring(11)).append('\n')
            append("用户: ").append(userText.replace("\n", " ").take(300)).append('\n')
            append("AJ: ").append(answer.replace("\n", " ").take(300)).append("\n\n")
        }
        f.appendText(entry)
    }

    /** 今天 + 昨天 + GLOBAL，超长截断 */
    fun recent(maxChars: Int = 4000): String {
        ensure()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        val days = ArrayList<String>()
        days += fmt.format(cal.time)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        days += fmt.format(cal.time)

        val sb = StringBuilder()
        val g = global().readText().trim()
        if (g.isNotEmpty()) sb.append("### GLOBAL.md\n").append(g).append("\n\n")
        for (d in days) {
            val f = dayFile(d)
            if (!f.exists()) continue
            val t = f.readText().trim()
            if (t.isEmpty()) continue
            sb.append("### $d\n").append(t).append("\n\n")
        }
        return sb.toString().take(maxChars)
    }

    fun today(): File {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        return dayFile(stamp)
    }

    fun clearToday() {
        val f = today()
        if (f.exists()) f.delete()
    }
}
