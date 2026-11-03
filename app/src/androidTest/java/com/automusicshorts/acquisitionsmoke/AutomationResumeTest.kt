package com.automusicshorts.acquisitionsmoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Invoked by a new instrumentation process after adb force-stop, without reinstall. */
@RunWith(AndroidJUnit4::class)
class AutomationResumeTest {
    @Test fun savedProjectAndCacheResumeAfterActualProcessStop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restored = AcquisitionRecoveryStore(File(context.filesDir, "qa-recovery.properties"),
            File(context.cacheDir, "automation-fixture")).load()!!
        assertEquals("COMPLETE", restored.stage)
        assertTrue(restored.song!!.file.isFile)
        assertTrue(restored.background!!.file.isFile)
        val pairing = PairingStore(context)
        assertTrue(pairing.token().isNotBlank())
        val client = MobileJobClient(pairing.cloudUrl(), pairing.token()) {
            URL("http://127.0.0.1:18765" + it.path).openConnection() as HttpURLConnection
        }
        assertTrue(client.request(restored.projectId!!, "status").getBoolean("download_ready"))
        assertTrue(File(context.filesDir, "qa-finished-videos/music-shorts-${restored.projectId}.mp4").isFile)
        val evidence = File(context.filesDir, "qa-automation-result.json")
        val result = JSONObject(evidence.readText())
        result.put("actual_process_restart", JSONObject().put("result", "PASS").put("cache_preserved", true)
            .put("pairing_preserved", true).put("project_preserved", true).put("redownload_or_upload", false))
        evidence.writeText(result.toString(2))
    }
}
