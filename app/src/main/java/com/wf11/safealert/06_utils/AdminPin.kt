package com.wf11.safealert.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.wf11.safealert.firebase.SitePinStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The site PIN an admin typed to enter the admin role, kept only on this phone for the PIN lookup approval.
 * Stored as Android Keystore AES-GCM ciphertext (non-exportable key) next to the PIN cache (prefs "site_pin").
 * It is valid only for the PIN version that was cached when it was saved; a newer version makes [load] return null.
 * Any Keystore failure stores nothing, and the caller asks for the PIN again.
 */
object AdminPin {
    private const val ALIAS = "safealert_admin_pin"
    private const val PREFS = "site_pin"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun save(ctx: Context, sc: String, pin: String) {
        runCatching {
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val blob = c.iv + c.doFinal(pin.toByteArray(Charsets.UTF_8))
            val ver = SitePinStore.cached(ctx, sc)?.ver ?: 0L
            prefs(ctx).edit().putString("adm_$sc", Base64.encodeToString(blob, Base64.NO_WRAP)).putLong("adm_ver_$sc", ver).apply()
        }
    }

    /** The saved PIN, or null when none is saved, the PIN version moved on since, or decryption fails. */
    fun load(ctx: Context, sc: String): String? = runCatching {
        val p = prefs(ctx)
        val blob = Base64.decode(p.getString("adm_$sc", null) ?: return null, Base64.NO_WRAP)
        if (p.getLong("adm_ver_$sc", -1L) != (SitePinStore.cached(ctx, sc)?.ver ?: 0L)) return null
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN))
        }
        String(c.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
    }.getOrNull()

    fun clear(ctx: Context, sc: String) {
        prefs(ctx).edit().remove("adm_$sc").remove("adm_ver_$sc").apply()
    }

    /** The PIN version the saved PIN belongs to, or -1 when none is saved. */
    fun savedVer(ctx: Context, sc: String): Long {
        val p = prefs(ctx)
        return if (p.contains("adm_$sc")) p.getLong("adm_ver_$sc", -1L) else -1L
    }
}
