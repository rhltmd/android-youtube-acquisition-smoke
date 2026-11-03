package com.automusicshorts.acquisitionsmoke

import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Actual Android APK/runtime + loopback protocol fixture. NOT live V2/provider E2E. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AutomationProtocolTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = "a".repeat(32)
    private fun pairing() = PairingStore(context)
    private fun local(url: URL) = URL("http://127.0.0.1:18765" + url.path).openConnection() as HttpURLConnection
    private fun client() = MobileJobClient(pairing().cloudUrl(), pairing().token(), ::local)

    private fun evidence(stage: String, result: JSONObject) {
        val file = File(context.filesDir, "qa-automation-result.json")
        val all = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        all.put(stage, result)
        all.put("actual_device_e2e", "NOT TESTED")
        all.put("cloud_provider_pipeline", "NOT TESTED IN THIS EMULATOR JOB")
        file.writeText(all.toString(2))
    }

    private fun asset(name: String): File {
        val file = File(context.cacheDir, "automation-fixture/$name")
        file.parentFile!!.mkdirs()
        if (!file.isFile) instrumentation.context.assets.open(name).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        return file
    }

    @Test fun test01_privatePairingImportAndNativeEngine() {
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".automation"))
        assertTrue(QaConnection.refreshPairing(context))
        assertTrue(pairing().token().isNotEmpty())
        val encrypted = context.getSharedPreferences("cloud-pairing", 0).getString("encrypted-token", "").orEmpty()
        assertFalse(encrypted.contains(pairing().token()))
        YoutubeDL.getInstance().init(context)
        evidence("native_runtime", JSONObject().put("result", "PASS").put("abi", android.os.Build.SUPPORTED_ABIS.first()))
    }

    @Test fun test02_realHttpMultipartReviewResumeAndDownload() {
        val upload = MobileUploadClient(::local)
        val source = PendingCloudUpload(asset("song.m4a"), "https://youtu.be/BaW_jenozKc", "Automation protocol fixture", 2000)
        val first = upload.upload(pairing().cloudUrl(), pairing().token(), source) {}
        val repeated = upload.upload(pairing().cloudUrl(), pairing().token(), source) {}
        assertEquals(first.projectId, repeated.projectId)
        assertEquals(id, first.projectId)
        val background = PendingBackgroundUpload(asset("background.mp4"), "https://youtu.be/jNQXAC9IVRw", id)
        assertEquals(id, upload.uploadBackground(pairing().cloudUrl(), pairing().token(), background) {}.projectId)
        val checkpoint = File(context.filesDir, "qa-recovery.properties")
        val cache = File(context.cacheDir, "automation-fixture")
        AcquisitionRecoveryStore(checkpoint, cache).save(AcquisitionRecovery("COMPLETE", background.youtubeUrl, source, id, background))
        assertEquals(id, AcquisitionRecoveryStore(checkpoint, cache).load()!!.projectId)
        assertEquals(source.requestId, AcquisitionRecoveryStore(checkpoint, cache).load()!!.song!!.requestId)
        assertEquals("needs_review", client().request(id, "start").getString("status"))
        val review = client().request(id, "review")
        assertEquals(1, review.getJSONArray("lines").length())
        assertEquals("completed", client().request(id, "review", JSONObject().put("approved", true)
            .put("revision", review.getString("revision"))).getString("status"))
        // A new client/process-side state view uses the saved project, no acquisition.
        assertTrue(client().request(id, "status").getBoolean("download_ready"))
        assertEquals("completed", client().request(id, "start").getString("status"))
        val downloaded = client().download(id, File(context.filesDir, "qa-finished-videos"))
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(downloaded.absolutePath)
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            assertEquals("1080", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("1920", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
        } finally { retriever.release() }
        val conn = local(URL("https://qa.invalid/qa-evidence"))
        conn.setRequestProperty(MOBILE_UPLOAD_TOKEN_HEADER, pairing().token())
        val server = try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) } finally { conn.disconnect() }
        val hash = MessageDigest.getInstance("SHA-256").digest(downloaded.readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue(hash == server.getString("download_sha256"))
        assertEquals(1, server.getInt("project_count"))
        assertEquals(2, server.getInt("source_attempts"))
        evidence("http_protocol_fixture", JSONObject().put("result", "PASS").put("idempotent_project_count", 1)
            .put("bytes", downloaded.length()).put("hash_matches", true).put("android_media_validation", "PASS")
            .put("review_and_resume", "PASS").put("provider_processing", "SCRIPTED FIXTURE - NOT E2E"))
    }

    @Test fun test03_transport530AndDnsFailurePreserveCache() {
        val directory = File(context.filesDir, "qa-error-downloads").apply { mkdirs() }
        val failureId = "0".repeat(28) + "0530"
        val existing = File(directory, "music-shorts-$failureId.mp4").apply { writeText("keep-existing") }
        try { client().download(failureId, directory); fail("530 must fail") }
        catch (error: CloudBridgeException) { assertEquals("SERVER_UNREACHABLE", error.category) }
        assertEquals("keep-existing", existing.readText())
        assertFalse(File(directory, existing.name + ".part").exists())
        val unreachable = MobileJobClient("https://does-not-exist.invalid", pairing().token())
        try { unreachable.download(id, directory); fail("DNS must fail") }
        catch (error: CloudBridgeException) {
            assertEquals("SERVER_UNREACHABLE", error.category)
            assertFalse(error.message.orEmpty().contains("does-not-exist"))
        }
        assertTrue(asset("song.m4a").isFile)
        evidence("transport_failures", JSONObject().put("http_530", "PASS").put("dns_failure", "PASS").put("cache_preserved", true))
    }

    @Test fun test04_privateConnectionRefreshWithoutTypingAndRejectRollback() {
        val file = File(context.filesDir, "qa-connection.json")
        val initial = JSONObject(file.readText())
        val token = pairing().token()
        val next = JSONObject(initial.toString()).put("cloud_url", "https://refreshed-qa.invalid")
            .put("generation", initial.getLong("generation") + 1)
        file.writeText(next.toString())
        assertTrue(QaConnection.refreshPairing(context))
        assertTrue(pairing().cloudUrl() == next.getString("cloud_url"))
        assertTrue(pairing().token() == token)
        file.writeText(initial.toString())
        assertFalse(QaConnection.refreshPairing(context))
        assertTrue(pairing().cloudUrl() == next.getString("cloud_url"))
        assertTrue(client().request(id, "status").getBoolean("download_ready"))
        evidence("private_connection_refresh", JSONObject().put("result", "PASS").put("token_rotated", false)
            .put("manual_typing", false).put("rollback_rejected", true))
    }

    @Test fun test05_actualQaApkLaunches() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val device = UiDevice.getInstance(instrumentation)
            assertTrue("start control visible", device.wait(Until.hasObject(By.text("다운로드 및 Cloud 프로젝트 생성")), 15000))
            assertTrue("recent project control reachable", UiScrollable(UiSelector().scrollable(true))
                .scrollIntoView(UiSelector().text("최근 Cloud 작업 이어보기")))
            evidence("apk_ui_launch", JSONObject().put("result", "PASS").put("build", BuildConfig.BUILD_COMMIT))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun test06_liveYoutubeAttemptIsReportedSeparately() {
        val report = JSONObject().put("network", "GITHUB_RUNNER_DATACENTER_NOT_PHONE_WIFI_5G")
        try {
            YoutubeDL.getInstance().init(context)
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            report.put("version", YoutubeDL.getInstance().versionName(context) ?: "unknown")
            for ((kind, video, selector) in listOf(
                Triple("song", "BaW_jenozKc", "bestaudio"),
                Triple("background", "jNQXAC9IVRw", MainActivity.BACKGROUND_FORMAT_SELECTOR),
            )) {
                val result = JSONObject()
                try {
                    val url = "https://www.youtube.com/watch?v=$video"
                    val metadata = YoutubeDLRequest(url).apply {
                        addOption("--socket-timeout", 15); addOption("--retries", 0); addOption("--extractor-retries", 0)
                    }
                    val info = YoutubeDL.getInstance().getInfo(metadata)
                    assertTrue(info.duration > 0)
                    result.put("metadata", "PASS")
                    val folder = File(context.cacheDir, "qa-youtube-$kind").apply { mkdirs() }
                    val request = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist"); addOption("--no-part"); addOption("--no-mtime")
                        addOption("--socket-timeout", 15); addOption("--retries", 0); addOption("--extractor-retries", 0)
                        addOption("--fragment-retries", 0); addOption("--max-filesize", "20M")
                        addOption("--format", selector); addOption("--output", File(folder, "%(id)s.%(ext)s").absolutePath)
                    }
                    YoutubeDL.getInstance().execute(request, "qa-$kind-${System.nanoTime()}") { _, _, _ -> }
                    val media = folder.listFiles()!!.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }.maxBy { it.length() }
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(media.absolutePath)
                        assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
                        val track = if (kind == "song") MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO else MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
                        assertEquals("yes", retriever.extractMetadata(track))
                    } finally { retriever.release() }
                    result.put("download", "PASS").put("android_media", "PASS").put("bytes", media.length())
                } catch (error: Throwable) {
                    val message = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" ")
                    result.put("download", "NOT VERIFIED").put("failure_type", error.javaClass.simpleName)
                        .put("bot_or_signin", message.contains("not a bot", true) || message.contains("sign in", true))
                        .put("http_403", message.contains("403")).put("js_error", message.contains("javascript", true))
                        .put("unavailable", message.contains("unavailable", true) || message.contains("private video", true))
                        .put("format_unavailable", message.contains("Requested format", true))
                }
                report.put(kind, result)
            }
        } catch (error: Throwable) {
            report.put("engine_or_update", "NOT VERIFIED").put("failure_type", error.javaClass.simpleName)
        }
        // A blocked public YouTube IP must not be mislabeled as a successful E2E.
        report.put("full_android_cloud_e2e", "NOT VERIFIED BY THIS TEST")
        evidence("live_youtube", report)
    }
}
