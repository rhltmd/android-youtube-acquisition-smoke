package com.automusicshorts.acquisitionsmoke

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class BootstrapEnvelopeException(reason: String) : IllegalArgumentException(reason)

internal class BootstrapDescriptor(
    val url: String,
    val generation: Long,
    val issuedAt: Long,
    val expiresAt: Long,
)

/** Wire format shared with the V2 Bridge publisher. The pairing secret stays on the device. */
@OptIn(ExperimentalEncodingApi::class)
internal object BootstrapEnvelope {
    private val format = Regex("v1\\.[A-Za-z0-9_-]{38,1024}")
    private val pairingFormat = Regex("[A-Za-z0-9_-]{43,128}")
    private val tunnelOrigin = Regex("https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.trycloudflare\\.com")
    private val fields = setOf("url", "generation", "issued_at", "expires_at")

    fun open(
        value: String,
        pairingToken: String,
        repoId: Long,
        codespaceId: Long,
        now: Long,
        previousGeneration: Long,
    ): BootstrapDescriptor {
        if (!pairingFormat.matches(pairingToken)) fail("PAIRING_KEY_FORMAT")
        if (repoId <= 0 || codespaceId <= 0) fail("TARGET_ID_FORMAT")
        if (now <= 0 || previousGeneration < 0) fail("INVALID_VALIDATION_STATE")
        if (!format.matches(value)) fail("INVALID_CONNECTION_ENVELOPE")
        val encoded = value.substring(3)
        val associatedData = "music-shorts-connection/v1:$repoId:$codespaceId".toByteArray(Charsets.US_ASCII)

        val payload = try {
            val raw = Base64.UrlSafe.decode(encoded.padEnd((encoded.length + 3) / 4 * 4, '='))
            if (raw.size <= 28 || Base64.UrlSafe.encode(raw).trimEnd('=') != encoded) {
                fail("CONNECTION_AUTHENTICATION_FAILED")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(pairingToken, associatedData), GCMParameterSpec(128, raw, 0, 12))
            cipher.updateAAD(associatedData)
            cipher.doFinal(raw, 12, raw.size - 12).decodeToString(throwOnInvalidSequence = true)
        } catch (error: BootstrapEnvelopeException) {
            throw error
        } catch (_: GeneralSecurityException) {
            fail("CONNECTION_AUTHENTICATION_FAILED")
        } catch (_: IllegalArgumentException) {
            fail("CONNECTION_AUTHENTICATION_FAILED")
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail("CONNECTION_AUTHENTICATION_FAILED")
        }

        val json = try {
            val tokener = JSONTokener(payload)
            val parsed = tokener.nextValue() as? JSONObject ?: fail("INVALID_CONNECTION_DESCRIPTOR")
            if (tokener.nextClean() != '\u0000') fail("INVALID_CONNECTION_DESCRIPTOR")
            parsed
        } catch (_: JSONException) {
            fail("INVALID_CONNECTION_DESCRIPTOR")
        }
        if (json.keys().asSequence().toSet() != fields) fail("INVALID_CONNECTION_DESCRIPTOR")
        val url = json.opt("url") as? String ?: fail("UNAPPROVED_TUNNEL_ORIGIN")
        if (!tunnelOrigin.matches(url)) fail("UNAPPROVED_TUNNEL_ORIGIN")
        val generation = positiveInteger(json, "generation")
        val issued = positiveInteger(json, "issued_at")
        val expires = positiveInteger(json, "expires_at")
        if (expires - issued !in 1L..900L) fail("INVALID_CONNECTION_LIFETIME")
        if (issued - now > 30 || expires <= now) fail("CONNECTION_NOT_CURRENT")
        if (generation <= previousGeneration) fail("CONNECTION_REPLAY")
        return BootstrapDescriptor(url, generation, issued, expires)
    }

    private fun positiveInteger(json: JSONObject, name: String): Long {
        val value = when (val raw = json.opt(name)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> fail("INVALID_CONNECTION_LIFETIME")
        }
        if (value <= 0) fail("INVALID_CONNECTION_LIFETIME")
        return value
    }

    private fun key(token: String, context: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val pseudorandomKey = mac.doFinal(token.toByteArray(Charsets.US_ASCII))
        mac.init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
        mac.update(context)
        return SecretKeySpec(mac.doFinal(byteArrayOf(1)), "AES")
    }

    private fun fail(reason: String): Nothing = throw BootstrapEnvelopeException(reason)
}
