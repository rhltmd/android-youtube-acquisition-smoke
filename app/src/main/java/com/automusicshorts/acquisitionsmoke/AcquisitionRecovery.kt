package com.automusicshorts.acquisitionsmoke

import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Properties
import java.util.UUID

internal data class AcquisitionRecovery(
    val stage: String,
    val backgroundUrl: String,
    val song: PendingCloudUpload?,
    val projectId: String?,
    val background: PendingBackgroundUpload?,
)

/** Private metadata only. Never contains pairing tokens, headers, or response logs. */
internal class AcquisitionRecoveryStore(private val file: File, private val cacheRoot: File) {
    private val stages = setOf("SONG_UPLOAD", "BACKGROUND_ACQUISITION", "BACKGROUND_UPLOAD", "COMPLETE")

    fun save(state: AcquisitionRecovery) {
        require(state.stage in stages)
        requireYoutubeUrl(state.backgroundUrl)
        if (state.stage != "SONG_UPLOAD") require(Regex("[0-9a-f]{32}").matches(state.projectId.orEmpty()))
        else require(state.projectId == null && state.background == null)
        state.background?.let {
            require(it.projectId == state.projectId && it.youtubeUrl == state.backgroundUrl)
        }
        val values = Properties().apply {
            setProperty("version", "1")
            setProperty("stage", state.stage)
            setProperty("backgroundUrl", state.backgroundUrl)
            state.projectId?.let { setProperty("projectId", it) }
            state.song?.let {
                requireYoutubeUrl(it.youtubeUrl)
                require(it.durationMs > 0)
                require(Regex("[0-9a-f]{32}").matches(it.requestId))
                setProperty("songUrl", it.youtubeUrl)
                setProperty("songName", it.projectName.take(80))
                setProperty("songDuration", it.durationMs.toString())
                setProperty("songRequestId", it.requestId)
                if (it.file.isFile) putFile(this, "song", it.file)
            }
            state.background?.let { if (it.file.isFile) putFile(this, "background", it.file) }
        }
        if (state.stage == "SONG_UPLOAD") require(values.containsKey("songFile"))
        if (state.stage == "BACKGROUND_UPLOAD") require(values.containsKey("backgroundFile"))
        val encoded = ByteArrayOutputStream().apply { values.store(this, "Acquisition recovery v1") }.toByteArray()
        require(encoded.size <= 16_384) { "Recovery checkpoint is too large" }
        require(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val pending = File(file.parentFile, file.name + ".pending")
        try {
            FileOutputStream(pending).use { output -> output.write(encoded); output.fd.sync() }
            check(pending.renameTo(file)) { "Unable to save recovery checkpoint" }
        } finally { pending.delete() }
    }

    fun load(): AcquisitionRecovery? {
        if (!file.exists()) return null
        require(file.length() in 1..16_384)
        val values = Properties().apply { file.inputStream().use { load(it) } }
        require(values.getProperty("version") == "1")
        val stage = values.getProperty("stage")
        require(stage in stages)
        val backgroundUrl = values.getProperty("backgroundUrl").also { requireYoutubeUrl(it) }
        val projectId = values.getProperty("projectId")
        if (stage != "SONG_UPLOAD") require(Regex("[0-9a-f]{32}").matches(projectId.orEmpty()))
        val song = readFile(values, "song")?.let {
            val url = values.getProperty("songUrl").also { requireYoutubeUrl(it) }
            val duration = values.getProperty("songDuration")?.toLongOrNull()
            require(duration != null && duration > 0)
            // Earlier development checkpoints had no request ID. Retry saves this upgrade before HTTP.
            val requestId = values.getProperty("songRequestId") ?: UUID.randomUUID().toString().replace("-", "")
            require(Regex("[0-9a-f]{32}").matches(requestId))
            PendingCloudUpload(it, url, values.getProperty("songName", "Android YouTube upload").take(80), duration, requestId)
        }
        val background = readFile(values, "background")?.let {
            require(!projectId.isNullOrBlank())
            PendingBackgroundUpload(it, backgroundUrl, projectId)
        }
        if (stage == "SONG_UPLOAD") require(song != null) { "Saved song cache is missing or changed" }
        if (stage == "BACKGROUND_UPLOAD") require(background != null) { "Saved background cache is missing or changed" }
        return AcquisitionRecovery(stage, backgroundUrl, song, projectId, background)
    }

    fun clear() { check(!file.exists() || file.delete()) { "Unable to clear recovery checkpoint" } }

    private fun putFile(values: Properties, prefix: String, media: File) {
        val root = cacheRoot.canonicalPath + File.separator
        val path = media.canonicalPath
        require(path.startsWith(root) && media.isFile && media.length() > 0)
        values.setProperty(prefix + "File", path.removePrefix(root).replace(File.separatorChar, '/'))
        values.setProperty(prefix + "Bytes", media.length().toString())
    }

    private fun readFile(values: Properties, prefix: String): File? {
        val relative = values.getProperty(prefix + "File") ?: return null
        require(!File(relative).isAbsolute && ':' !in relative && '\\' !in relative &&
            relative.split('/').none { it == ".." || it.isEmpty() })
        val media = File(cacheRoot, relative).canonicalFile
        require(media.path.startsWith(cacheRoot.canonicalPath + File.separator))
        val bytes = values.getProperty(prefix + "Bytes")?.toLongOrNull()
        return media.takeIf { bytes != null && bytes > 0 && it.isFile && it.length() == bytes }
    }

    private fun requireYoutubeUrl(value: String?) {
        require(value != null && value.length <= 2048)
        val uri = URI(value)
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && uri.rawUserInfo == null &&
            (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")))
    }
}
