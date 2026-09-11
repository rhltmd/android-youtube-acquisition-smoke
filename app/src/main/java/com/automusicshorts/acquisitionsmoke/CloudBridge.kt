package com.automusicshorts.acquisitionsmoke

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val MOBILE_UPLOAD_TOKEN_HEADER = "X-Music-Shorts-Token"

data class PendingCloudUpload(
    val file: File,
    val youtubeUrl: String,
    val projectName: String,
    val durationMs: Long,
)

data class PendingBackgroundUpload(
    val file: File,
    val youtubeUrl: String,
    val projectId: String,
)

data class CloudUploadResult(
    val projectId: String,
    val uploadedBytes: Long,
    val responseBody: String,
)

data class BackgroundUploadResult(
    val projectId: String,
    val uploadedBytes: Long,
    val durationMs: Long,
    val responseBody: String,
)

class CloudBridgeException(val category: String, message: String, cause: Throwable? = null) :
    Exception(message, cause)

class PairingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun cloudUrl(): String = preferences.getString(CLOUD_URL_KEY, "").orEmpty()

    fun token(): String {
        val stored = preferences.getString(TOKEN_KEY, null) ?: return ""
        return runCatching { decrypt(stored) }.getOrElse {
            preferences.edit().remove(TOKEN_KEY).apply()
            ""
        }
    }

    fun save(cloudUrl: String, token: String) {
        preferences.edit()
            .putString(CLOUD_URL_KEY, cloudUrl)
            .putString(TOKEN_KEY, encrypt(token))
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(":", limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "cloud-pairing"
        private const val CLOUD_URL_KEY = "cloud-url"
        private const val TOKEN_KEY = "encrypted-token"
        private const val KEY_ALIAS = "music-shorts-mobile-bridge"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

class MobileUploadClient {
    fun upload(
        cloudBaseUrl: String,
        token: String,
        pending: PendingCloudUpload,
        progress: (Int) -> Unit,
    ): CloudUploadResult {
        val endpoint = mobileSourceEndpoint(cloudBaseUrl)
        val boundary = "MusicShorts-${System.currentTimeMillis()}"
        val hash = sha256(pending.file)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 180_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty(MOBILE_UPLOAD_TOKEN_HEADER, token)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            DataOutputStream(BufferedOutputStream(connection.outputStream)).use { output ->
                writeField(output, boundary, "youtube_url", pending.youtubeUrl)
                writeField(output, boundary, "original_filename", pending.file.name)
                writeField(output, boundary, "size", pending.file.length().toString())
                writeField(output, boundary, "sha256", hash)
                writeField(output, boundary, "duration_ms", pending.durationMs.toString())
                writeField(output, boundary, "project_name", pending.projectName.take(80))
                output.writeBytes("--$boundary\r\n")
                output.writeBytes(
                    "Content-Disposition: form-data; name=\"media\"; filename=\"${pending.file.name}\"\r\n",
                )
                output.writeBytes("Content-Type: ${contentType(pending.file)}\r\n\r\n")
                val total = pending.file.length().coerceAtLeast(1L)
                var sent = 0L
                pending.file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sent += count
                        progress(((sent * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
                output.writeBytes("\r\n--$boundary--\r\n")
            }

            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw CloudBridgeException(classifyHttpStatus(status), "HTTP $status: ${responseMessage(responseBody)}")
            }
            val payload = JSONObject(responseBody)
            if (!payload.optBoolean("ok")) {
                throw CloudBridgeException("UPLOAD_FAILURE", responseMessage(responseBody))
            }
            val projectId = payload.optString("project_id")
            if (projectId.isBlank()) {
                throw CloudBridgeException("PROJECT_CREATION_FAILURE", "Cloud response has no project_id")
            }
            return CloudUploadResult(
                projectId = projectId,
                uploadedBytes = payload.optLong("uploaded_bytes", pending.file.length()),
                responseBody = responseBody,
            )
        } catch (error: CloudBridgeException) {
            throw error
        } catch (error: UnknownHostException) {
            throw CloudBridgeException("SERVER_UNREACHABLE", error.message ?: "Unknown host", error)
        } catch (error: ConnectException) {
            throw CloudBridgeException("SERVER_UNREACHABLE", error.message ?: "Connection failed", error)
        } catch (error: SocketTimeoutException) {
            throw CloudBridgeException("SERVER_UNREACHABLE", error.message ?: "Connection timed out", error)
        } catch (error: Exception) {
            throw CloudBridgeException("UPLOAD_FAILURE", error.message ?: error.javaClass.name, error)
        } finally {
            connection.disconnect()
        }
    }

    fun uploadBackground(
        cloudBaseUrl: String,
        token: String,
        pending: PendingBackgroundUpload,
        progress: (Int) -> Unit,
    ): BackgroundUploadResult {
        val endpoint = mobileBackgroundEndpoint(cloudBaseUrl)
        val boundary = "MusicShortsBackground-${System.currentTimeMillis()}"
        val hash = sha256(pending.file)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 180_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty(MOBILE_UPLOAD_TOKEN_HEADER, token)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            DataOutputStream(BufferedOutputStream(connection.outputStream)).use { output ->
                backgroundUploadFields(pending, hash).forEach { (name, value) ->
                    writeField(output, boundary, name, value)
                }
                output.writeBytes("--$boundary\r\n")
                output.writeBytes(
                    "Content-Disposition: form-data; name=\"media\"; filename=\"${pending.file.name}\"\r\n",
                )
                output.writeBytes("Content-Type: ${backgroundContentType(pending.file)}\r\n\r\n")
                val total = pending.file.length().coerceAtLeast(1L)
                var sent = 0L
                pending.file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sent += count
                        progress(((sent * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
                output.writeBytes("\r\n--$boundary--\r\n")
            }

            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw CloudBridgeException(classifyHttpStatus(status), "HTTP $status: ${responseMessage(responseBody)}")
            }
            val payload = JSONObject(responseBody)
            if (!payload.optBoolean("ok")) {
                throw CloudBridgeException("UPLOAD_FAILURE", responseMessage(responseBody))
            }
            val projectId = payload.optString("project_id")
            if (projectId != pending.projectId) {
                throw CloudBridgeException("PROJECT_CREATION_FAILURE", "Cloud response project_id mismatch")
            }
            return BackgroundUploadResult(
                projectId = projectId,
                uploadedBytes = payload.optLong("uploaded_bytes", pending.file.length()),
                durationMs = payload.optLong("duration_ms"),
                responseBody = responseBody,
            )
        } catch (error: CloudBridgeException) {
            throw error
        } catch (error: UnknownHostException) {
            throw CloudBridgeException("SERVER_UNREACHABLE", error.message ?: "Unknown host", error)
        } catch (error: ConnectException) {
            throw CloudBridgeException("SERVER_UNREACHABLE", error.message ?: "Connection failed", error)
        } catch (error: SocketTimeoutException) {
            throw CloudBridgeException("SERVER_UNREACHABLE", error.message ?: "Connection timed out", error)
        } catch (error: Exception) {
            throw CloudBridgeException("UPLOAD_FAILURE", error.message ?: error.javaClass.name, error)
        } finally {
            connection.disconnect()
        }
    }

    private fun writeField(output: DataOutputStream, boundary: String, name: String, value: String) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        output.write(value.toByteArray(Charsets.UTF_8))
        output.writeBytes("\r\n")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun contentType(file: File): String = when (file.extension.lowercase()) {
        "m4a" -> "audio/mp4"
        "webm" -> "audio/webm"
        "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    private fun backgroundContentType(file: File): String = when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    private fun responseMessage(responseBody: String): String {
        return runCatching {
            val payload = JSONObject(responseBody)
            payload.optString("message").ifBlank { payload.optString("detail") }
        }.getOrDefault(responseBody).take(2_000)
    }
}

internal fun mobileSourceEndpoint(baseUrl: String): URL {
    val normalized = baseUrl.trim().trimEnd('/')
    val uri = runCatching { URI(normalized) }.getOrNull()
        ?: throw CloudBridgeException("SERVER_UNREACHABLE", "Cloud URL이 올바르지 않습니다.")
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
        throw CloudBridgeException("SERVER_UNREACHABLE", "HTTPS Cloud URL을 입력해주세요.")
    }
    return URL("$normalized/mobile-source")
}

internal fun mobileBackgroundEndpoint(baseUrl: String): URL {
    val normalized = baseUrl.trim().trimEnd('/')
    val uri = runCatching { URI(normalized) }.getOrNull()
        ?: throw CloudBridgeException("SERVER_UNREACHABLE", "Cloud URL이 올바르지 않습니다.")
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
        throw CloudBridgeException("SERVER_UNREACHABLE", "HTTPS Cloud URL을 입력해주세요.")
    }
    return URL("$normalized/mobile-background")
}

internal fun backgroundUploadFields(
    pending: PendingBackgroundUpload,
    sha256: String,
): Map<String, String> = linkedMapOf(
    "project_id" to pending.projectId,
    "youtube_url" to pending.youtubeUrl,
    "original_filename" to pending.file.name,
    "size" to pending.file.length().toString(),
    "sha256" to sha256,
)

internal fun classifyHttpStatus(status: Int): String = when (status) {
    401, 403 -> "AUTH_FAILURE"
    400, 413, 415, 422 -> "CLOUD_VALIDATION_FAILURE"
    in 500..599 -> "PROJECT_CREATION_FAILURE"
    else -> "UPLOAD_FAILURE"
}
