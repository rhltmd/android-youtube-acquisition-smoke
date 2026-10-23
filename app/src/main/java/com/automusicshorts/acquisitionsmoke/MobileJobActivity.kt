package com.automusicshorts.acquisitionsmoke

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Resumable Cloud status/review/download screen; acquisition is unchanged. */
class MobileJobActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private lateinit var projectId: String
    private lateinit var client: MobileJobClient
    private lateinit var status: TextView
    private lateinit var review: Button
    private lateinit var download: Button
    private lateinit var retryProcessing: Button
    private var retryRevision: String? = null
    private var visible = false
    private var pollAgain = true
    private val poll = Runnable { fetchStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectId = intent.getStringExtra("project_id").orEmpty()
        val pairing = PairingStore(this)
        if (!Regex("[0-9a-f]{32}").matches(projectId)) { finish(); return }
        client = MobileJobClient(pairing.cloudUrl(), pairing.token())
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        status = TextView(this).apply { textSize = 18f; text = "Cloud 작업 연결 중"; setTextIsSelectable(true) }
        content.addView(status)
        review = Button(this).apply { text = "가사·번역·타이밍 확인"; visibility = View.GONE; setOnClickListener { loadReview() } }
        content.addView(review)
        download = Button(this).apply { text = "완성 MP4 다운로드"; visibility = View.GONE; setOnClickListener { downloadVideo() } }
        content.addView(download)
        retryProcessing = Button(this).apply {
            text = "중단된 처리 다시 시도"
            visibility = View.GONE
            setOnClickListener { confirmProcessingRetry() }
        }
        content.addView(retryProcessing)
        content.addView(Button(this).apply { text = "상태 다시 확인"; setOnClickListener { pollAgain = true; fetchStatus() } })
        setContentView(ScrollView(this).apply { addView(content) })
        if (savedInstanceState == null) runTask {
            // Idempotent server start never regenerates completed stages or
            // bypasses a pending review. Conflict can mean interrupted work.
            try { client.request(projectId, "start") } catch (error: Exception) {
                showError(error)
            }
            showState(client.request(projectId, "status"))
        }
    }

    override fun onResume() { super.onResume(); visible = true; handler.postDelayed(poll, 1000) }
    override fun onPause() { visible = false; handler.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); executor.shutdownNow(); super.onDestroy() }

    private fun runTask(action: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try { action() } catch (error: Exception) { showError(error) }
            finally {
                busy.set(false)
                handler.post { if (visible && pollAgain) { handler.removeCallbacks(poll); handler.postDelayed(poll, 5000) } }
            }
        }
    }

    private fun fetchStatus() = runTask { showState(client.request(projectId, "status")) }

    private fun showState(state: JSONObject) {
        val phase = state.optString("status")
        pollAgain = phase == "running"
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            status.text = "Project: $projectId\n단계: ${state.optString("stage")}\n상태: $phase\n가사: ${state.optInt("line_count")}줄"
            if (phase == "interrupted") status.append("\n서버 실행이 중단됐습니다. 저장된 내용을 확인해주세요.")
            if (phase == "needs_attention") status.append("\n확인이 필요합니다: ${state.optString("error")}")
            review.visibility = if (phase in setOf("needs_review", "needs_attention", "interrupted")) View.VISIBLE else View.GONE
            download.visibility = if (state.optBoolean("download_ready")) View.VISIBLE else View.GONE
            retryRevision = if (state.isNull("retry_revision")) null else state.optString("retry_revision")
            retryProcessing.visibility = if (mobileRetryAllowed(phase, state.optString("stage"),
                    state.optBoolean("retry_available")) && retryRevision?.length == 64) View.VISIBLE else View.GONE
        }
    }

    private fun confirmProcessingRetry() {
        val revision = retryRevision ?: return
        AlertDialog.Builder(this).setTitle("실패한 처리 재시도")
            .setMessage("완료된 단계와 저장된 가사는 유지합니다. 아직 완료되지 않은 처리를 다시 요청할까요? API 호출이 다시 발생할 수 있으며, 렌더 전 검토는 생략하지 않습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("재시도") { _, _ ->
                runTask {
                    val body = JSONObject().put("retry", true).put("revision", revision)
                    showState(client.request(projectId, "retry", body))
                }
            }.show()
    }

    private fun loadReview() = runTask {
        val data = client.request(projectId, "review")
        showReview(data)
    }

    private fun showReview(data: JSONObject) {
        val lines = data.getJSONArray("lines")
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
            content.addView(TextView(this).apply { text = "수정할 줄을 누르세요. 저장 후 다시 검토해야 렌더를 시작합니다." })
            lateinit var dialog: AlertDialog
            var complete = lines.length() > 0
            for (index in 0 until lines.length()) {
                val line = lines.getJSONObject(index)
                val japanese = lineText(line, "japanese")
                val korean = lineText(line, "korean")
                if (korean.isBlank() || runCatching {
                    reviewEditTimes(japanese, korean, lineText(line, "start_ms"), lineText(line, "end_ms"))
                }.isFailure) complete = false
                content.addView(Button(this).apply {
                    isAllCaps = false
                    text = "${index + 1}. ${lineText(line, "start_ms")}–${lineText(line, "end_ms")} ms\n$japanese\n$korean"
                    setOnClickListener { dialog.dismiss(); editReviewLine(data.getString("revision"), line) }
                })
            }
            dialog = AlertDialog.Builder(this).setTitle("최종 가사·번역·타이밍 검토")
                .setView(ScrollView(this).apply { addView(content) })
                .setNegativeButton("돌아가기", null)
                .setPositiveButton("확인 완료 · 렌더 시작") { _, _ ->
                    runTask {
                        val approved = JSONObject().put("approved", true).put("revision", data.getString("revision"))
                        showState(client.request(projectId, "review", approved))
                    }
                }.create()
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = complete
        }
    }

    private fun lineText(line: JSONObject, key: String): String =
        if (line.isNull(key)) "" else line.optString(key)

    private fun editReviewLine(revision: String, line: JSONObject) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        fun field(label: String, key: String, numeric: Boolean = false): EditText {
            content.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setText(lineText(line, key))
                content.addView(this)
            }
        }
        val japanese = field("일본어 원문", "japanese")
        val korean = field("한국어 자막", "korean")
        val start = field("시작 (ms)", "start_ms", true)
        val end = field("종료 (ms)", "end_ms", true)
        val errorView = TextView(this)
        content.addView(errorView)
        val dialog = AlertDialog.Builder(this).setTitle("가사 한 줄 수정")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("취소", null).setPositiveButton("저장 후 다시 검토", null).create()
        dialog.show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            val original = japanese.text.toString()
            val translated = korean.text.toString()
            val times = try { reviewEditTimes(original, translated, start.text.toString(), end.text.toString()) }
                catch (error: IllegalArgumentException) { errorView.text = error.message; return@setOnClickListener }
            if (busy.get()) { errorView.text = "현재 요청이 끝난 후 저장해주세요."; return@setOnClickListener }
            save.isEnabled = false
            val body = JSONObject().put("revision", revision).put("line_id", line.getString("line_id"))
                .put("japanese", original).put("korean", translated).put("start_ms", times.first).put("end_ms", times.second)
            runTask {
                try {
                    val updated = client.request(projectId, "review-line", body)
                    runOnUiThread { if (!isDestroyed) { dialog.dismiss(); showReview(updated) } }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (!isDestroyed) {
                            save.isEnabled = true
                            errorView.text = "저장되지 않았습니다. 입력은 유지됩니다. 충돌이면 최신 가사를 다시 열어주세요.\n" +
                                (if (error is CloudBridgeException) error.message else error.javaClass.simpleName)
                        }
                    }
                }
            }
        }
    }

    private fun downloadVideo() = runTask {
        runOnUiThread { status.text = "완성 MP4 다운로드 중" }
        val file = client.download(projectId, File(filesDir, "finished-videos"))
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            require(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
            require((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
        } finally { metadata.release() }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MusicShorts")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("다운로드 폴더를 만들 수 없습니다.")
            try {
                contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("다운로드 폴더에 저장할 수 없습니다.")
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            }
            runOnUiThread { status.text = "MP4 저장 완료\nDownloads/MusicShorts/${file.name}\n${file.length()} bytes" }
        } else {
            runOnUiThread {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "video/mp4"
                    putExtra(Intent.EXTRA_TITLE, file.name)
                }, 41)
            }
        }
    }

    @Deprecated("Platform callback retained for API 24–28")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runTask {
                val file = File(filesDir, "finished-videos/music-shorts-$projectId.mp4")
                contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("영상 저장에 실패했습니다.")
                runOnUiThread { status.text = "MP4 저장 완료" }
            }
        }
    }

    private fun showError(error: Exception) {
        pollAgain = false
        runOnUiThread {
            if (!isDestroyed) status.text = "Cloud 연결/처리에 실패했습니다. 기존 프로젝트는 보존됩니다.\n" +
                (if (error is CloudBridgeException) error.message else error.javaClass.simpleName)
        }
    }
}
