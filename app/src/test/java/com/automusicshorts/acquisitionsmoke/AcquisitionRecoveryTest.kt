package com.automusicshorts.acquisitionsmoke

import java.io.File
import java.util.Properties
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AcquisitionRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val songUrl = "https://youtu.be/song"
    private val backgroundUrl = "https://www.youtube.com/watch?v=background"
    private val projectId = "a".repeat(32)

    private fun fixture(): Triple<File, File, AcquisitionRecoveryStore> {
        val root = temp.newFolder()
        val cache = File(root, "cache").apply { mkdir() }
        val state = File(root, "private/recovery.properties")
        return Triple(cache, state, AcquisitionRecoveryStore(state, cache))
    }

    private fun song(cache: File) = PendingCloudUpload(
        File(cache, "song.webm").apply { writeBytes(byteArrayOf(1, 2, 3)) }, songUrl, "노래", 3000,
    )

    private fun background(cache: File) = PendingBackgroundUpload(
        File(cache, "background").apply { mkdir() }.resolve("video.mp4").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) },
        backgroundUrl, projectId,
    )

    @Test fun newStoreRestoresSongRetryWithoutAcquisition() {
        val (cache, file, store) = fixture()
        val song = song(cache)
        store.save(AcquisitionRecovery("SONG_UPLOAD", backgroundUrl, song, null, null))
        val restored = AcquisitionRecoveryStore(file, cache).load()!!
        assertEquals("SONG_UPLOAD", restored.stage)
        assertEquals(song.file.canonicalFile, restored.song!!.file)
        assertEquals(3000L, restored.song!!.durationMs)
        assertEquals(songUrl, restored.song!!.youtubeUrl)
        assertEquals("노래", restored.song!!.projectName)
        assertNull(restored.projectId)
    }

    @Test fun backgroundRetryPreservesSameProjectAndBothFiles() {
        val (cache, file, store) = fixture()
        val song = song(cache)
        val background = background(cache)
        store.save(AcquisitionRecovery("BACKGROUND_UPLOAD", backgroundUrl, song, projectId, background))
        val restored = AcquisitionRecoveryStore(file, cache).load()!!
        assertEquals(projectId, restored.background!!.projectId)
        assertEquals(backgroundUrl, restored.background!!.youtubeUrl)
        assertEquals(background.file.canonicalFile, restored.background!!.file)
        assertEquals(song.file.canonicalFile, restored.song!!.file)
    }

    @Test fun acknowledgedSongCanResumeBackgroundEvenWhenSongCacheEvicted() {
        val (cache, _, store) = fixture()
        val song = song(cache)
        store.save(AcquisitionRecovery("BACKGROUND_ACQUISITION", backgroundUrl, song, projectId, null))
        assertTrue(song.file.delete())
        val restored = store.load()!!
        assertEquals("BACKGROUND_ACQUISITION", restored.stage)
        assertEquals(projectId, restored.projectId)
        assertNull(restored.song)
    }

    @Test fun completedProjectSurvivesBothCacheFilesEvicted() {
        val (cache, _, store) = fixture()
        val song = song(cache)
        val background = background(cache)
        store.save(AcquisitionRecovery("COMPLETE", backgroundUrl, song, projectId, background))
        song.file.delete()
        background.file.delete()
        assertEquals("COMPLETE", store.load()!!.stage)
        assertEquals(projectId, store.load()!!.projectId)
    }

    @Test fun missingRequiredSongFailsWithoutDeletingCheckpoint() {
        val (cache, file, store) = fixture()
        val song = song(cache)
        store.save(AcquisitionRecovery("SONG_UPLOAD", backgroundUrl, song, null, null))
        song.file.delete()
        assertThrows(IllegalArgumentException::class.java) { store.load() }
        assertTrue(file.isFile)
    }

    @Test fun changedRequiredBackgroundFailsWithoutDeletingAnyData() {
        val (cache, file, store) = fixture()
        val background = background(cache)
        store.save(AcquisitionRecovery("BACKGROUND_UPLOAD", backgroundUrl, song(cache), projectId, background))
        background.file.appendBytes(byteArrayOf(9))
        assertThrows(IllegalArgumentException::class.java) { store.load() }
        assertTrue(file.isFile)
        assertEquals(5L, background.file.length())
    }

    @Test fun pathsOutsideCacheAreRejectedAndPreviousStateSurvives() {
        val (cache, file, store) = fixture()
        val song = song(cache)
        store.save(AcquisitionRecovery("SONG_UPLOAD", backgroundUrl, song, null, null))
        val previous = file.readBytes()
        val external = temp.newFile().apply { writeText("not cache") }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(AcquisitionRecovery("SONG_UPLOAD", backgroundUrl, song.copy(file = external), null, null))
        }
        assertArrayEquals(previous, file.readBytes())
        assertTrue(external.exists())
        assertFalse(File(file.parentFile, file.name + ".pending").exists())
    }

    @Test fun corruptedRelativePathsCannotEscapeCache() {
        val (cache, file, store) = fixture()
        val song = song(cache)
        for (path in listOf("../outside.webm", "/tmp/outside.webm", "C:\\outside.webm", "nested/../../outside", "nested//file")) {
            store.save(AcquisitionRecovery("SONG_UPLOAD", backgroundUrl, song, null, null))
            rewrite(file) { setProperty("songFile", path) }
            assertThrows(IllegalArgumentException::class.java) { store.load() }
        }
    }

    @Test fun invalidStateVersionUrlAndProjectAreRejected() {
        val (cache, file, store) = fixture()
        val state = AcquisitionRecovery("BACKGROUND_ACQUISITION", backgroundUrl, song(cache), projectId, null)
        for ((key, value) in listOf("version" to "2", "stage" to "UNKNOWN", "backgroundUrl" to "https://youtube.com.evil.invalid/", "projectId" to "../escape")) {
            store.save(state)
            rewrite(file) { setProperty(key, value) }
            assertThrows(IllegalArgumentException::class.java) { store.load() }
        }
    }

    @Test fun invalidDurationAndMixedProjectBackgroundCannotBeSaved() {
        val (cache, _, store) = fixture()
        val song = song(cache)
        assertThrows(IllegalArgumentException::class.java) {
            store.save(AcquisitionRecovery("SONG_UPLOAD", backgroundUrl, song.copy(durationMs = 0), null, null))
        }
        val otherBackground = background(cache).copy(projectId = "b".repeat(32))
        assertThrows(IllegalArgumentException::class.java) {
            store.save(AcquisitionRecovery("BACKGROUND_UPLOAD", backgroundUrl, song, projectId, otherBackground))
        }
    }

    @Test fun checkpointIsBoundedAndContainsOnlyRecoveryMetadata() {
        val (cache, file, store) = fixture()
        store.save(AcquisitionRecovery("SONG_UPLOAD", backgroundUrl, song(cache), null, null))
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        assertEquals(setOf("version", "stage", "backgroundUrl", "songUrl", "songName", "songDuration", "songFile", "songBytes"), properties.stringPropertyNames())
        assertEquals("song.webm", properties.getProperty("songFile"))
        file.writeBytes(ByteArray(16_385))
        assertThrows(IllegalArgumentException::class.java) { store.load() }
    }

    @Test fun clearingCheckpointDoesNotDeleteMedia() {
        val (cache, file, store) = fixture()
        val song = song(cache)
        store.save(AcquisitionRecovery("SONG_UPLOAD", backgroundUrl, song, null, null))
        store.clear()
        assertFalse(file.exists())
        assertTrue(song.file.isFile)
        assertNull(store.load())
    }

    private fun rewrite(file: File, update: Properties.() -> Unit) {
        val values = Properties().apply { file.inputStream().use { load(it) }; update() }
        file.outputStream().use { values.store(it, null) }
    }
}
