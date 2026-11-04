package com.automusicshorts.acquisitionsmoke

import android.content.Intent
import android.content.Context
import android.content.ContentUris
import android.media.MediaMetadataRetriever
import android.graphics.Rect
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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

    private fun awaitActivity(activity: android.app.Activity, condition: (List<View>) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                val views = mutableListOf<View>()
                fun collect(view: View) {
                    views.add(view)
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(activity.window.decorView)
                ready = condition(views)
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("Actual Activity did not reach the expected download/storage state")
    }

    @Test fun test07_actualDownloadButtonPublishesReadableMediaStoreVideo() {
        // The HTTP backend is scripted. Only the connection factory is injected;
        // the real Activity button, downloader and MediaStore publishing code run.
        // No real Cloud credential or user project is available in this emulator.
        assertTrue(client().request(id, "status").getBoolean("download_ready"))
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        fun publishedIds(): Set<Long> {
            val ids = mutableSetOf<Long>()
            resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.IS_PENDING}=0",
                arrayOf(Environment.DIRECTORY_DOWNLOADS + "/MusicShorts/"), null)!!.use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getLong(0))
            }
            return ids
        }
        val before = publishedIds()
        val activity = instrumentation.startActivitySync(Intent(context, MobileJobActivity::class.java)
            .putExtra("project_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val result = JSONObject().put("result", "FAIL")
        try {
            // onCreate may already have begun a status request. Wait for that
            // one task to finish before substituting the loopback test client.
            val busy = MobileJobActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }
                .get(activity) as java.util.concurrent.atomic.AtomicBoolean
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (busy.get() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertFalse("initial Activity request finished", busy.get())
            instrumentation.runOnMainSync {
                MobileJobActivity::class.java.getDeclaredField("client").apply { isAccessible = true }
                    .set(activity, client())
            }
            awaitActivity(activity) { views ->
                views.filterIsInstance<Button>().single { it.text.toString() == "상태 다시 확인" }.performClick()
                true
            }
            awaitActivity(activity) { views ->
                views.filterIsInstance<Button>().single { it.text.toString() == "완성 MP4 다운로드" }.isShown
            }
            awaitActivity(activity) { views ->
                val button = views.filterIsInstance<Button>().single { it.text.toString() == "완성 MP4 다운로드" }
                button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                assertTrue(button.isShown && button.getGlobalVisibleRect(Rect()))
                button.performClick()
                true
            }
            awaitActivity(activity) { views ->
                views.filterIsInstance<TextView>().any { it.text.toString().startsWith("MP4 저장 완료") }
            }
            val created = publishedIds() - before
            assertEquals("one published Downloads item", 1, created.size)
            val uri = ContentUris.withAppendedId(collection, created.single())
            assertEquals("video/mp4", resolver.getType(uri))
            val file = File(context.filesDir, "finished-videos/music-shorts-$id.mp4")
            val expected = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val exported = resolver.openInputStream(uri)!!.use { it.readBytes() }
            assertEquals(file.length(), exported.size.toLong())
            assertArrayEquals(expected, MessageDigest.getInstance("SHA-256").digest(exported))
            val media = MediaMetadataRetriever()
            try {
                media.setDataSource(context, uri)
                assertEquals("yes", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                assertEquals("yes", media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertTrue(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
            } finally { media.release() }
            // Private restart fixture, not published in logs/artifacts.
            File(context.filesDir, "qa-media-publish.json").writeText(JSONObject()
                .put("uri", uri.toString()).put("bytes", file.length())
                .put("sha256", expected.joinToString("") { "%02x".format(it) }).toString())
            result.put("result", "PASS").put("actual_button", true).put("published_to_downloads", true)
                .put("pending_cleared", true).put("hash_matches", true).put("android_media_validation", "PASS")
                .put("transport", "INJECTED LOOPBACK SCRIPTED BACKEND - NOT CLOUD E2E")
        } finally {
            evidence("download_public_storage", result)
            instrumentation.runOnMainSync { activity.finish() }
        }
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
        try { upload.upload(pairing().cloudUrl(), "deliberately-wrong-token", source) {}; fail("wrong auth must fail") }
        catch (error: CloudBridgeException) { assertEquals("AUTH_FAILURE", error.category) }
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
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val result = JSONObject().put("result", "FAIL")
        try {
            instrumentation.runOnMainSync {
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            instrumentation.waitForIdleSync()
            result.put("screen_on", device.isScreenOn).put("foreground_package", device.currentPackageName)
            // API 35's accessibility tree did not expose a UiScrollable. Inspect
            // and interact with the *actual Activity views*, not mocked widgets.
            instrumentation.runOnMainSync {
                val views = mutableListOf<View>()
                fun collect(view: View) {
                    views.add(view)
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(activity.window.decorView)
                val start = views.filterIsInstance<Button>().single { it.text.toString() == "다운로드 및 Cloud 프로젝트 생성" }
                val recent = views.filterIsInstance<Button>().single { it.text.toString() == "최근 Cloud 작업 이어보기" }
                start.requestRectangleOnScreen(Rect(0, 0, start.width, start.height), true)
                assertTrue("actual start view visible", start.isShown && start.getGlobalVisibleRect(Rect()))
                start.performClick() // Empty source must be rejected, not start acquisition.
                val source = views.filterIsInstance<EditText>().single { it.hint?.toString() == "https://www.youtube.com/watch?v=..." }
                assertFalse("actual empty-source validation", source.error.isNullOrEmpty())
                recent.requestRectangleOnScreen(Rect(0, 0, recent.width, recent.height), true)
                assertTrue("actual resume view visible", recent.isShown && recent.getGlobalVisibleRect(Rect()))
                result.put("backend", "actual Android View instrumentation")
                    .put("start_reachable", true).put("recent_reachable", true).put("empty_source_validation", "PASS")
            }
            result.put("result", "PASS").put("build", BuildConfig.BUILD_COMMIT)
        } finally {
            evidence("apk_ui_launch", result)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun test06_liveYoutubeAttemptIsReportedSeparately() {
        val report = JSONObject().put("network", "GITHUB_RUNNER_DATACENTER_NOT_PHONE_WIFI_5G")
        try {
            YoutubeDL.getInstance().init(context)
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            report.put("version", YoutubeDL.getInstance().versionName(context) ?: "unknown")
            for ((kind, video, selector) in listOf(
                Triple("song", "ukGZbsOO6mk", "bestaudio"),
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
