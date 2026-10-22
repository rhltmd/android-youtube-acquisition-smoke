package com.automusicshorts.acquisitionsmoke

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal fun mobileJobEndpoint(base: String, projectId: String, action: String): URL {
    require(Regex("[0-9a-f]{32}").matches(projectId)) { "Invalid project ID" }
    require(action in setOf("start", "status", "review", "review-line", "download")) { "Invalid job action" }
    val uri = URI(base.trim().trimEnd('/'))
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.path.orEmpty().isEmpty()) { "Cloud URL must be an HTTPS server address" }
    return URL("$uri/mobile-projects/$projectId/$action")
}

internal fun reviewEditTimes(japanese: String, korean: String, start: String, end: String): Pair<Int, Int> {
    require(japanese.length <= 2000 && korean.length <= 2000) { "한 줄은 2,000자 이하로 입력해주세요." }
    val startMs = start.trim().toIntOrNull()
    val endMs = end.trim().toIntOrNull()
    require(startMs != null && endMs != null && startMs >= 0 && endMs > startMs) {
        "시작/종료를 밀리초 정수로 입력해주세요. 종료는 시작보다 뒤여야 합니다."
    }
    return startMs to endMs
}

internal class MobileJobClient(
    private val base: String,
    private val token: String,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private fun connection(projectId: String, action: String, method: String): HttpURLConnection =
        openConnection(mobileJobEndpoint(base, projectId, action)).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false // Never forward the pairing secret to a different host.
            setRequestProperty(MOBILE_UPLOAD_TOKEN_HEADER, token)
            setRequestProperty("Accept", "application/json")
        }

    fun request(projectId: String, action: String, body: JSONObject? = null): JSONObject {
        val method = if (action == "start" || body != null) "POST" else "GET"
        val conn = connection(projectId, action, method)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val code = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw CloudBridgeException(classifyHttpStatus(status), "HTTP $status: $code")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    fun download(projectId: String, directory: File): File {
        directory.mkdirs()
        val target = File(directory, "music-shorts-$projectId.mp4")
        val partial = File(directory, "music-shorts-$projectId.mp4.part")
        val conn = connection(projectId, "download", "GET")
        try {
            val status = conn.responseCode
            if (status != 200) throw CloudBridgeException(classifyHttpStatus(status), "Download HTTP $status")
            require(conn.contentType?.substringBefore(';') == "video/mp4") { "Unexpected download type" }
            val expected = conn.getHeaderFieldLong("Content-Length", -1)
            require(expected > 0 && expected < directory.usableSpace - 16L * 1024 * 1024) {
                "영상 크기를 확인할 수 없거나 저장 공간이 부족합니다."
            }
            conn.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= expected) { "Unexpected download length" }
                        output.write(buffer, 0, count)
                    }
                    require(bytes == expected) { "Incomplete download" }
                }
            }
            require(partial.renameTo(target)) { "영상 저장에 실패했습니다." }
            return target
        } finally {
            partial.delete()
            conn.disconnect()
        }
    }
}
