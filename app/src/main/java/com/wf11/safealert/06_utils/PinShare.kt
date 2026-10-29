package com.wf11.safealert.utils

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PIN lookup answer sealing. The site PIN travels only as AES-GCM ciphertext to the requester's one-time EC (P-256) key.
 * The request ID is the AEAD aad, so an answer cannot be moved to another request. Pure JVM (no android.*) so unit tests run it.
 */
object PinShare {
    /** A PIN lookup request lives this long (the requester timeout and the admin listener window). */
    const val REQ_TTL_MS = 120_000L

    data class Sealed(val epk: String, val iv: String, val ct: String)

    private const val INFO = "safealert-pin-share-v1"

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicB64(pub: PublicKey): String = Base64.getEncoder().encodeToString(pub.encoded)

    /** Admin side: encrypt [pin] to the requester's public key with a fresh ephemeral key pair. */
    fun seal(pin: String, requesterPubB64: String, aad: String): Sealed {
        val eph = newKeyPair()
        val key = hkdf(agree(eph.private, decodePublic(requesterPubB64)))
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ct = c.doFinal(pin.toByteArray(Charsets.UTF_8))
        val b = Base64.getEncoder()
        return Sealed(publicB64(eph.public), b.encodeToString(iv), b.encodeToString(ct))
    }

    /** Requester side: null on any failure (wrong key, wrong aad, tampered data). */
    fun open(sealed: Sealed, myPrivate: PrivateKey, aad: String): String? = runCatching {
        val key = hkdf(agree(myPrivate, decodePublic(sealed.epk)))
        val d = Base64.getDecoder()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, d.decode(sealed.iv)))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        String(c.doFinal(d.decode(sealed.ct)), Charsets.UTF_8)
    }.getOrNull()

    private fun decodePublic(b64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))

    private fun agree(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(priv); doPhase(pub, true) }.generateSecret()

    // HKDF-SHA256: extract with a 32-byte zero salt, expand one block (32 bytes = the AES-256 key).
    private fun hkdf(ikm: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        expand.update(INFO.toByteArray(Charsets.UTF_8))
        expand.update(1.toByte())
        return expand.doFinal()
    }
}
