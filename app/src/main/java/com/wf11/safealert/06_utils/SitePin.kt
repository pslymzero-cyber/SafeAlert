package com.wf11.safealert.utils

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Per-site 10-digit settings PIN — check values and the decision only (pure Kotlin, no Android).
 * The server keeps [Pub] (salt + hash, readable) and a separate gate value (never readable) that proves the
 * current PIN when changing it. The iteration limits must equal the numbers in database.rules.json.
 */
object SitePin {
    const val LEN = 10
    const val INITIAL = "0000000000"
    const val ITER = 60_000
    const val MIN_ITER = 50_000
    const val MAX_ITER = 1_000_000
    const val SALT_BYTES = 16

    /** Public check value: salt and hash as lowercase hex (32 and 64 chars). */
    data class Pub(val salt: String, val hash: String, val iter: Int, val ver: Long, val at: Long, val by: String)

    data class NewRecord(val pub: Pub, val gate: String)

    sealed interface State {
        data class Has(val pub: Pub) : State
        /** The server confirmed there is no PIN yet. */
        object None : State
        /** No cache and the server could not be reached. */
        object Unknown : State
    }

    sealed interface Verdict {
        object Ok : Verdict
        object FirstSet : Verdict
        object Wrong : Verdict
        /** Not counted as a failed try. */
        object NeedOnline : Verdict
    }

    fun hashOf(pin: String, saltHex: String, iter: Int): String = pbkdf2(pin, unhex(saltHex), iter)

    fun gateOf(pin: String, saltHex: String, iter: Int): String =
        pbkdf2(pin, unhex(saltHex) + "gate".toByteArray(Charsets.UTF_8), iter)

    /** Fresh salt; [Pub.at] is a placeholder (the server stamps it). */
    fun newRecord(pin: String, ver: Long, by: String, random: SecureRandom, iter: Int = ITER): NewRecord {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }.let(::hex)
        return NewRecord(Pub(salt, hashOf(pin, salt, iter), iter, ver, 0L, by), gateOf(pin, salt, iter))
    }

    fun matches(pin: String, pub: Pub): Boolean =
        MessageDigest.isEqual(hashOf(pin, pub.salt, pub.iter).toByteArray(), pub.hash.toByteArray())

    fun isValidNew(pin: String): Boolean = pin.length == LEN && pin.all { it in '0'..'9' } && pin != INITIAL

    fun check(input: String, state: State): Verdict = when (state) {
        is State.Has -> if (matches(input, state.pub)) Verdict.Ok else Verdict.Wrong
        State.None -> if (input == INITIAL) Verdict.FirstSet else Verdict.Wrong
        State.Unknown -> Verdict.NeedOnline
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iter: Int): String =
        hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iter, 256)).encoded)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
