package com.automusicshorts.acquisitionsmoke

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    private lateinit var urlInput: EditText
    private lateinit var startButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        acceptSharedUrl(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedUrl(intent)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        content.addView(TextView(this).apply {
            text = "Android YouTube Acquisition Smoke"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "쿠키·로그인·프록시·수동 PO Token 없이, 앱 내부 yt-dlp가 실제 media를 받는지만 확인합니다. 파일은 앱 cacheDir에만 저장됩니다."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(18))
        })

        urlInput = EditText(this).apply {
            hint = "https://www.youtube.com/watch?v=..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
        }
        content.addView(urlInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        startButton = Button(this).apply {
            text = "다운로드 테스트"
            setOnClickListener { startSmoke() }
        }
        content.addView(startButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        content.addView(progressBar, LinearLayout.LayoutParams.MATCH_PARENT, dp(22))

        statusText = TextView(this).apply {
            text = "대기 중"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        }
        content.addView(statusText)

        resultText = TextView(this).apply {
            text = "결과가 여기에 표시됩니다."
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        content.addView(resultText)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun acceptSharedUrl(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val candidate = HTTPS_URL.find(shared)?.value?.trimEnd(')', ']', '}', ',', '.', ';')
        if (!candidate.isNullOrBlank()) {
            urlInput.setText(candidate)
            statusText.text = "공유된 링크를 받았습니다. 다운로드 테스트를 눌러주세요."
        }
    }

    private fun startSmoke() {
        if (!running.compareAndSet(false, true)) return

        val url = normalizeYoutubeUrl(urlInput.text.toString())
        if (url == null) {
            running.set(false)
            urlInput.error = "유효한 HTTPS YouTube URL을 입력해주세요."
            return
        }

        startButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.text = "yt-dlp runtime 초기화 중"
        resultText.text = "Network: ${networkLabel()}\n"

        executor.execute {
            val workDir = File(cacheDir, "youtube-acquisition-smoke")
            try {
                recreateDirectory(workDir)
                YoutubeDL.getInstance().init(applicationContext)

                ui(status = "최신 stable yt-dlp 확인 중")
                val updateStatus = YoutubeDL.getInstance().updateYoutubeDL(
                    applicationContext,
                    YoutubeDL.UpdateChannel.STABLE,
                )
                val ytDlpVersion = YoutubeDL.getInstance().versionName(applicationContext)
                    ?: YoutubeDL.getInstance().version(applicationContext)
                    ?: "unknown"

                ui(
                    status = "Metadata 요청 중",
                    details = "Network: ${networkLabel()}\nyt-dlp update: $updateStatus\nyt-dlp version: $ytDlpVersion",
                )
                val info = YoutubeDL.getInstance().getInfo(url)
                val metadataReport = buildString {
                    appendLine("Metadata: PASS")
                    appendLine("Title: ${info.title ?: "(unknown)"}")
                    appendLine("Video ID: ${info.id ?: "(unknown)"}")
                    appendLine("Metadata duration: ${info.duration} s")
                    appendLine("Network: ${networkLabel()}")
                    appendLine("yt-dlp update: $updateStatus")
                    appendLine("yt-dlp version: $ytDlpVersion")
                }

                ui(status = "bestaudio 단일 스트림 다운로드 중", details = metadataReport)
                val outputTemplate = File(workDir, "%(id)s.%(ext)s").absolutePath
                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("--no-part")
                    addOption("--restrict-filenames")
                    addOption(
                        "--format",
                        "bestaudio",
                    )
                    addOption("--output", outputTemplate)
                }
                val processId = "acquisition-smoke-${System.currentTimeMillis()}"
                val response = YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
                    runOnUiThread {
                        progressBar.progress = progress.toInt().coerceIn(0, 100)
                        statusText.text = "다운로드 ${"%.1f".format(Locale.US, progress)}% · ETA ${eta}s"
                        if (line.contains("ERROR", ignoreCase = true)) {
                            resultText.text = metadataReport + "\n" + line.takeLast(2_000)
                        }
                    }
                }

                val mediaFile = workDir.listFiles()
                    ?.filter { it.isFile && it.length() > 0L && !it.name.endsWith(".part") }
                    ?.maxByOrNull { it.length() }
                    ?: error("yt-dlp exited successfully but no media file exists in cacheDir")
                val media = inspectMedia(mediaFile)
                val outputTail = response.out.takeLast(1_500).trim()

                ui(
                    status = "SUCCESS",
                    progress = 100,
                    details = buildString {
                        append(metadataReport)
                        appendLine("Download: PASS")
                        appendLine("Exit code: ${response.exitCode}")
                        appendLine("Elapsed: ${"%.2f".format(Locale.US, response.elapsedTime / 1000.0)} s")
                        appendLine("Cache file: ${mediaFile.name}")
                        appendLine("File size: ${formatBytes(mediaFile.length())}")
                        appendLine("Android media validation: PASS")
                        appendLine("Duration: ${media.durationMs} ms")
                        appendLine("MIME: ${media.mime ?: "unknown"}")
                        appendLine("Has video: ${media.hasVideo ?: "unknown"}")
                        appendLine("Has audio: ${media.hasAudio ?: "unknown"}")
                        if (outputTail.isNotEmpty()) appendLine("\nyt-dlp output tail:\n$outputTail")
                    },
                )
            } catch (error: Throwable) {
                val message = generateSequence(error) { it.cause }
                    .mapNotNull { it.message }
                    .distinct()
                    .joinToString("\nCaused by: ")
                    .ifBlank { error.javaClass.name }
                ui(
                    status = "FAIL",
                    details = buildString {
                        appendLine("Network: ${networkLabel()}")
                        appendLine("Failure type: ${classifyFailure(message)}")
                        appendLine("Error:")
                        append(message.takeLast(8_000))
                    },
                )
            } finally {
                running.set(false)
                runOnUiThread {
                    startButton.isEnabled = true
                    progressBar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun normalizeYoutubeUrl(raw: String): String? {
        val candidate = HTTPS_URL.find(raw.trim())?.value ?: return null
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true)) return null
        val host = parsed.host?.lowercase(Locale.US) ?: return null
        val allowed = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        return candidate.takeIf { allowed }
    }

    private fun recreateDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            error("Unable to clear previous app cache")
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            error("Unable to create app cache directory")
        }
    }

    private fun inspectMedia(file: File): MediaInspection {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: error("Android could not read media duration")
            if (duration <= 0L) error("Android reported a non-positive media duration")
            MediaInspection(
                durationMs = duration,
                mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),
            )
        } finally {
            retriever.release()
        }
    }

    private fun networkLabel(): String {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return "none"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
    }

    private fun classifyFailure(message: String): String = when {
        message.contains("Sign in to confirm", ignoreCase = true) -> "BOT_OR_SIGN_IN"
        message.contains("HTTP Error 403", ignoreCase = true) -> "HTTP_403"
        message.contains("PO Token", ignoreCase = true) -> "PO_TOKEN"
        message.contains("JavaScript", ignoreCase = true) ||
            message.contains("js-runtimes", ignoreCase = true) -> "JS_RUNTIME"
        else -> "OTHER"
    }

    private fun ui(status: String, details: String? = null, progress: Int? = null) {
        runOnUiThread {
            statusText.text = status
            details?.let { resultText.text = it }
            progress?.let { progressBar.progress = it.coerceIn(0, 100) }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.2f MiB (%d bytes)".format(
            Locale.US,
            bytes / (1024.0 * 1024.0),
            bytes,
        )
        bytes >= 1024L -> "%.2f KiB (%d bytes)".format(Locale.US, bytes / 1024.0, bytes)
        else -> "$bytes bytes"
    }

    private data class MediaInspection(
        val durationMs: Long,
        val mime: String?,
        val hasVideo: String?,
        val hasAudio: String?,
    )

    companion object {
        private val HTTPS_URL = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE)
    }
}
