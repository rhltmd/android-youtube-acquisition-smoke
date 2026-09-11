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
    private val uploadClient = MobileUploadClient()

    private lateinit var pairingStore: PairingStore
    private lateinit var cloudUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var backgroundUrlInput: EditText
    private lateinit var startButton: Button
    private lateinit var retryButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    @Volatile private var pendingUpload: PendingCloudUpload? = null
    @Volatile private var pendingBackgroundUpload: PendingBackgroundUpload? = null
    @Volatile private var pendingBackgroundUrl: String? = null
    @Volatile private var createdProjectId: String? = null
    @Volatile private var retryStage: RetryStage? = null
    @Volatile private var latestReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairingStore = PairingStore(this)
        setContentView(buildUi())
        cloudUrlInput.setText(pairingStore.cloudUrl())
        tokenInput.setText(pairingStore.token())
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
            text = "노래 bestaudio와 배경 단일 video stream을 휴대폰에서 받은 뒤 같은 Cloud 프로젝트에 자동 연결합니다."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(18))
        })

        cloudUrlInput = EditText(this).apply {
            hint = "Cloud URL (https://...-8765.app.github.dev)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 2
        }
        content.addView(cloudUrlInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        tokenInput = EditText(this).apply {
            hint = "Pairing token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
        }
        content.addView(tokenInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        urlInput = EditText(this).apply {
            hint = "https://www.youtube.com/watch?v=..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
        }
        content.addView(urlInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        backgroundUrlInput = EditText(this).apply {
            hint = "Background YouTube URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
        }
        content.addView(
            backgroundUrlInput,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        startButton = Button(this).apply {
            text = "다운로드 및 Cloud 프로젝트 생성"
            setOnClickListener { startSmoke() }
        }
        content.addView(startButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        retryButton = Button(this).apply {
            text = "실패 단계 다시 시도"
            visibility = View.GONE
            setOnClickListener { retryUpload() }
        }
        content.addView(retryButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

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
        val backgroundUrl = normalizeYoutubeUrl(backgroundUrlInput.text.toString())
        if (backgroundUrl == null) {
            running.set(false)
            backgroundUrlInput.error = "유효한 HTTPS YouTube URL을 입력해주세요."
            return
        }
        val pairing = pairingSettings() ?: run {
            running.set(false)
            return
        }
        pairingStore.save(pairing.first, pairing.second)

        startButton.isEnabled = false
        pendingUpload = null
        pendingBackgroundUpload = null
        pendingBackgroundUrl = backgroundUrl
        createdProjectId = null
        retryStage = null
        retryButton.visibility = View.GONE
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
                val acquisitionReport = buildString {
                    append(metadataReport)
                    appendLine("Acquisition: PASS")
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
                }
                val acquired = PendingCloudUpload(
                    file = mediaFile,
                    youtubeUrl = url,
                    projectName = (info.title ?: "Android YouTube upload").take(80),
                    durationMs = media.durationMs,
                )
                pendingUpload = acquired
                latestReport = acquisitionReport
                uploadSongAndContinue(
                    acquired,
                    backgroundUrl,
                    pairing.first,
                    pairing.second,
                    acquisitionReport,
                    workDir,
                )
            } catch (error: Throwable) {
                val message = generateSequence(error) { it.cause }
                    .mapNotNull { it.message }
                    .distinct()
                    .joinToString("\nCaused by: ")
                    .ifBlank { error.javaClass.name }
                ui(
                    status = "Acquisition: FAIL",
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

    private fun retryUpload() {
        if (!running.compareAndSet(false, true)) return
        val pairing = pairingSettings() ?: run {
            running.set(false)
            return
        }
        pairingStore.save(pairing.first, pairing.second)
        startButton.isEnabled = false
        retryButton.isEnabled = false
        progressBar.progress = 0
        executor.execute {
            try {
                when (retryStage) {
                    RetryStage.SONG_UPLOAD -> {
                        val acquired = pendingUpload
                        val backgroundUrl = pendingBackgroundUrl
                        if (acquired == null || !acquired.file.isFile || backgroundUrl.isNullOrBlank()) {
                            error("재시도할 song acquisition 파일이 없습니다.")
                        }
                        uploadSongAndContinue(
                            acquired,
                            backgroundUrl,
                            pairing.first,
                            pairing.second,
                            latestReport,
                            File(cacheDir, "youtube-acquisition-smoke"),
                        )
                    }
                    RetryStage.BACKGROUND_ACQUISITION -> {
                        val projectId = createdProjectId ?: error("재시도할 Cloud project ID가 없습니다.")
                        val backgroundUrl = pendingBackgroundUrl ?: error("재시도할 background URL이 없습니다.")
                        acquireAndUploadBackground(
                            projectId,
                            backgroundUrl,
                            pairing.first,
                            pairing.second,
                            latestReport,
                            File(cacheDir, "youtube-acquisition-smoke"),
                        )
                    }
                    RetryStage.BACKGROUND_UPLOAD -> {
                        val pending = pendingBackgroundUpload
                        if (pending == null || !pending.file.isFile) {
                            error("재시도할 background acquisition 파일이 없습니다.")
                        }
                        uploadBackground(pending, pairing.first, pairing.second, latestReport)
                    }
                    null -> error("재시도할 단계가 없습니다.")
                }
            } catch (error: Throwable) {
                ui(
                    status = "재시도 실패",
                    retryVisible = true,
                    details = latestReport + "\nRetry error: " + (error.message ?: error.javaClass.name),
                )
            } finally {
                running.set(false)
                runOnUiThread {
                    startButton.isEnabled = true
                    retryButton.isEnabled = true
                }
            }
        }
    }

    private fun uploadSongAndContinue(
        acquired: PendingCloudUpload,
        backgroundUrl: String,
        cloudUrl: String,
        token: String,
        acquisitionReport: String,
        workDir: File,
    ) {
        ui(status = "Song acquisition: PASS · Song upload 중", details = acquisitionReport, progress = 0)
        try {
            val uploaded = uploadClient.upload(cloudUrl, token, acquired) { progress ->
                ui(status = "Song acquisition: PASS · Song upload $progress%", progress = progress)
            }
            createdProjectId = uploaded.projectId
            retryStage = null
            val songReport = buildString {
                append(acquisitionReport)
                appendLine("Song upload: PASS")
                appendLine("Song uploaded bytes: ${uploaded.uploadedBytes}")
                appendLine("Project ID: ${uploaded.projectId}")
            }
            latestReport = songReport
            acquireAndUploadBackground(
                uploaded.projectId,
                backgroundUrl,
                cloudUrl,
                token,
                songReport,
                workDir,
            )
        } catch (error: CloudBridgeException) {
            retryStage = RetryStage.SONG_UPLOAD
            ui(
                status = "Song acquisition: PASS · Song upload: FAIL",
                retryVisible = true,
                details = buildString {
                    append(acquisitionReport)
                    appendLine("Song upload: FAIL")
                    appendLine("Failure type: ${error.category}")
                    appendLine("Error: ${error.message}")
                    appendLine("다운로드한 song cache 파일은 유지됐습니다.")
                },
            )
        }
    }

    private fun acquireAndUploadBackground(
        projectId: String,
        backgroundUrl: String,
        cloudUrl: String,
        token: String,
        songReport: String,
        workDir: File,
    ) {
        try {
            ui(status = "Song upload: PASS · Background metadata 요청 중", details = songReport, progress = 0)
            val info = YoutubeDL.getInstance().getInfo(backgroundUrl)
            val backgroundDirectory = File(workDir, "background")
            recreateDirectory(backgroundDirectory)
            val outputTemplate = File(backgroundDirectory, "%(id)s.%(ext)s").absolutePath
            val request = YoutubeDLRequest(backgroundUrl).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--no-part")
                addOption("--restrict-filenames")
                addOption("--format", BACKGROUND_FORMAT_SELECTOR)
                addOption("--output", outputTemplate)
            }
            ui(status = "Background 단일 video stream 다운로드 중", details = songReport)
            val response = YoutubeDL.getInstance().execute(
                request,
                "background-acquisition-${System.currentTimeMillis()}",
            ) { progress, eta, _ ->
                ui(
                    status = "Background 다운로드 ${"%.1f".format(Locale.US, progress)}% · ETA ${eta}s",
                    progress = progress.toInt().coerceIn(0, 100),
                )
            }
            val mediaFile = backgroundDirectory.listFiles()
                ?.filter { it.isFile && it.length() > 0L && !it.name.endsWith(".part") }
                ?.maxByOrNull { it.length() }
                ?: error("yt-dlp exited successfully but no background media exists in cacheDir")
            val media = inspectMedia(mediaFile)
            if (!media.hasVideo.equals("yes", ignoreCase = true) && media.hasVideo != "1") {
                error("Android validation found no video track in background media")
            }
            val backgroundReport = buildString {
                append(songReport)
                appendLine("Background metadata: PASS")
                appendLine("Background title: ${info.title ?: "(unknown)"}")
                appendLine("Background acquisition: PASS")
                appendLine("Background exit code: ${response.exitCode}")
                appendLine("Background file: ${mediaFile.name}")
                appendLine("Background size: ${formatBytes(mediaFile.length())}")
                appendLine("Background duration: ${media.durationMs} ms")
                appendLine("Background has video: ${media.hasVideo}")
            }
            val pending = PendingBackgroundUpload(
                file = mediaFile,
                youtubeUrl = backgroundUrl,
                projectId = projectId,
            )
            pendingBackgroundUpload = pending
            latestReport = backgroundReport
            retryStage = null
            uploadBackground(pending, cloudUrl, token, backgroundReport)
        } catch (error: Throwable) {
            retryStage = RetryStage.BACKGROUND_ACQUISITION
            ui(
                status = "Song upload: PASS · Background acquisition: FAIL",
                retryVisible = true,
                details = buildString {
                    append(songReport)
                    appendLine("Background acquisition: FAIL")
                    appendLine("Project ID: $projectId")
                    appendLine("Error: ${error.message ?: error.javaClass.name}")
                    appendLine("Song project와 song cache는 유지됐습니다.")
                },
            )
        }
    }

    private fun uploadBackground(
        pending: PendingBackgroundUpload,
        cloudUrl: String,
        token: String,
        backgroundReport: String,
    ) {
        try {
            ui(status = "Background acquisition: PASS · Background upload 중", details = backgroundReport, progress = 0)
            val uploaded = uploadClient.uploadBackground(cloudUrl, token, pending) { progress ->
                ui(status = "Background acquisition: PASS · Background upload $progress%", progress = progress)
            }
            retryStage = null
            ui(
                status = "Song: PASS · Background: PASS",
                progress = 100,
                retryVisible = false,
                details = buildString {
                    append(backgroundReport)
                    appendLine("Background upload: PASS")
                    appendLine("Background uploaded bytes: ${uploaded.uploadedBytes}")
                    appendLine("Background Cloud duration: ${uploaded.durationMs} ms")
                    appendLine("Project ID: ${uploaded.projectId}")
                },
            )
        } catch (error: CloudBridgeException) {
            retryStage = RetryStage.BACKGROUND_UPLOAD
            ui(
                status = "Background acquisition: PASS · Background upload: FAIL",
                retryVisible = true,
                details = buildString {
                    append(backgroundReport)
                    appendLine("Background upload: FAIL")
                    appendLine("Project ID: ${pending.projectId}")
                    appendLine("Failure type: ${error.category}")
                    appendLine("Error: ${error.message}")
                    appendLine("Background cache와 기존 Cloud project는 유지됐습니다.")
                },
            )
        }
    }

    private fun pairingSettings(): Pair<String, String>? {
        val cloudUrl = cloudUrlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString()
        val uri = runCatching { Uri.parse(cloudUrl) }.getOrNull()
        if (uri?.scheme?.equals("https", ignoreCase = true) != true || uri.host.isNullOrBlank()) {
            cloudUrlInput.error = "유효한 HTTPS Cloud URL을 입력해주세요."
            return null
        }
        if (token.isBlank()) {
            tokenInput.error = "Pairing token을 입력해주세요."
            return null
        }
        return cloudUrl to token
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

    private fun ui(
        status: String,
        details: String? = null,
        progress: Int? = null,
        retryVisible: Boolean? = null,
    ) {
        runOnUiThread {
            statusText.text = status
            details?.let { resultText.text = it }
            progress?.let { progressBar.progress = it.coerceIn(0, 100) }
            retryVisible?.let { retryButton.visibility = if (it) View.VISIBLE else View.GONE }
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

    private enum class RetryStage {
        SONG_UPLOAD,
        BACKGROUND_ACQUISITION,
        BACKGROUND_UPLOAD,
    }

    companion object {
        private val HTTPS_URL = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE)
        internal const val BACKGROUND_FORMAT_SELECTOR =
            "bestvideo[height<=1080][vcodec^=avc1]/best[height<=1080][vcodec^=avc1]/" +
                "bestvideo[height<=1080]/best[height<=1080]"
    }
}
