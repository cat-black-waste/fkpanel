package com.fk.panel

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** 后端 API 客户端（HttpURLConnection，无第三方依赖） */
object Api {

    const val USER = "fk"
    const val PASS = "fk123456"
    private const val NTFY = "https://ntfy.sh/fj-zaoo3u1q7wvj/json?poll=1"
    private const val TS = "100.99.151.98"
    private const val PORT = 8080

    val pool = Executors.newFixedThreadPool(4)

    @Volatile var base: String = ""
        private set

    private fun authHeader(): String {
        val raw = "$USER:$PASS".toByteArray()
        return "Basic " + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun open(path: String, timeout: Int = 15000): HttpURLConnection {
        require(base.isNotEmpty()) { "未连接" }
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = timeout
        c.setRequestProperty("Authorization", authHeader())
        return c
    }

    fun get(path: String, timeout: Int = 15000): String =
        open(path, timeout).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    fun post(path: String, json: String? = null, timeout: Int = 15000): String {
        val c = open(path, timeout)
        c.requestMethod = "POST"
        c.doOutput = true
        if (json != null) c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { os -> if (json != null) os.write(json.toByteArray()) }
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    // ---------- 地址解析 ----------
    private fun probe(host: String): Boolean = try {
        val c = URL("http://$host/stat").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 4000
        c.setRequestProperty("Authorization", authHeader())
        c.responseCode == 200
    } catch (e: Exception) { false }

    /** 依次尝试 本地 → tailscale → ntfy 最新 IPv6，成功则设置 base */
    fun resolve(): String {
        val candidates = mutableListOf("127.0.0.1:$PORT", "$TS:$PORT")
        try {
            val body = URL(NTFY).openStream().use { it.readBytes().toString(Charsets.UTF_8) }
            body.lines().reversed().forEach { line ->
                val i = line.indexOf("u0_a354@")
                if (i >= 0) {
                    val sb = StringBuilder()
                    for (ch in line.substring(i + 8)) {
                        if (ch == ':' || ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F') sb.append(ch) else break
                    }
                    if (sb.contains(":") && sb.isNotEmpty()) candidates.add(0, "[$sb]:$PORT")
                }
            }
        } catch (_: Exception) {}
        for (cand in candidates.distinct()) {
            if (probe(cand)) { base = "http://$cand"; return base }
        }
        throw IllegalStateException("找不到设备")
    }

    // ---------- 业务接口 ----------
    fun stat(): JSONObject = JSONObject(get("/stat"))

    fun ls(path: String): JSONObject = JSONObject(get("/ls?p=" + enc(path)))

    fun runCmd(cmd: String, root: Boolean): JSONObject {
        val o = JSONObject().put("cmd", cmd).put("root", root)
        return JSONObject(post("/cmd", o.toString(), timeout = 60000))
    }

    fun touch(type: String, x: Int, y: Int) {
        pool.execute { runCatching { post("/touch?t=$type&x=$x&y=$y") } }
    }

    fun tap(x: Int, y: Int) {
        pool.execute { runCatching { post("/tap?x=$x&y=$y") } }
    }

    fun key(code: Int) {
        pool.execute { runCatching { post("/key?n=$code") } }
    }

    fun act(action: String): String = post("/act?a=$action")

    /** 下载到 App 私有目录，回调进度（0..100） */
    fun download(remote: String, targetDir: File, onProgress: (Int) -> Unit): File {
        val c = open("/dl?p=" + enc(remote), timeout = 120000)
        val total = c.contentLengthLong
        val name = remote.substringAfterLast('/')
        val out = File(targetDir, name)
        var done = 0L
        c.inputStream.use { input: InputStream ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    os.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        onProgress(100)
        return out
    }

    /** 上传本地文件到远端目录 */
    fun upload(local: File, remoteDir: String, onProgress: (Int) -> Unit): String {
        val path = remoteDir.trimEnd('/') + "/" + local.name
        val c = open("/up?p=" + enc(path), timeout = 120000)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(local.length())
        c.outputStream.use { os ->
            local.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024); var sent = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    os.write(buf, 0, n)
                    sent += n
                    onProgress((sent * 100 / local.length().coerceAtLeast(1)).toInt())
                }
            }
        }
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 视频流地址 */
    fun streamUrl(): String = base + "/stream.h264"

    fun auth(): String = authHeader()

    fun parseListings(o: JSONObject): Pair<String, List<Triple<String, Boolean, Long>>> {
        val path = o.optString("path", "")
        val arr: JSONArray = o.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<Triple<String, Boolean, Long>>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            out.add(Triple(it.optString("name"), it.optBoolean("dir"), it.optLong("size", 0)))
        }
        return path to out
    }
}
