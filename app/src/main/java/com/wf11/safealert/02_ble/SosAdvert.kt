package com.wf11.safealert.ble

/**
 * SOS advertising extension — appends 3 bytes after the service data only while an SOS is active.
 *
 * Service data layout: byte0 = state payload (unchanged), byte1 = extension flags (unchanged),
 * byte2 = SOS episode (1..255, 0 = none), byte3-4 = short ID of the latest beacon of the SOS sender
 * (big-endian, 0 = none).
 *
 * Backward compatibility: older versions read service data by index (byte0, and byte1 on newer ones) and
 * never check its length, so they ignore the appended bytes. Older versions read the whole manufacturer data
 * as the ID, so it must never grow.
 *
 * Packet budget: non-connectable advertising has no flags AD. UUID list 4 + service data (4+len) +
 * manufacturer (4+ID len) <= 31. With the extension the service data is 5 bytes, so the ID carries at most
 * 12 bytes (real IDs are PIT 5B, AUTO 11B, so no impact).
 */
object SosAdvert {
    const val ID_MAX = 15
    const val EXT_ID_MAX = 12
    const val PACKET_MAX = 31

    /** Total advertising packet bytes: 16-bit service UUID list AD + service data AD + manufacturer data AD. */
    fun advertBytes(idLen: Int, dataLen: Int): Int = 4 + (4 + dataLen) + (4 + idLen)

    /** ID bytes carried in the advertisement. Truncated to 12 bytes during the SOS extension, otherwise 15 bytes. */
    fun idBytes(deviceId: String, sosExt: Boolean): ByteArray =
        deviceId.toByteArray(Charsets.UTF_8).take(if (sosExt) EXT_ID_MAX else ID_MAX).toByteArray()

    /** Builds the service data. With episode 0 it stays the plain 2 bytes. */
    fun serviceData(byte0: Byte, ext: Int, episode: Int, hint: Int): ByteArray =
        if (episode == 0) byteArrayOf(byte0, ext.toByte())
        else byteArrayOf(byte0, ext.toByte(), episode.toByte(), (hint shr 8).toByte(), hint.toByte())

    /** Reads byte2 unsigned; 0 if absent. No allocation (scan path). */
    fun decodeEpisode(svc: ByteArray?): Int =
        if (svc != null && svc.size > 2) svc[2].toInt() and 0xFF else 0

    /** Reads byte3-4 as unsigned 16-bit; 0 if either is missing. */
    fun decodeHint(svc: ByteArray?): Int =
        if (svc != null && svc.size > 4) ((svc[3].toInt() and 0xFF) shl 8) or (svc[4].toInt() and 0xFF) else 0

    /** Short beacon-key ID: CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) of uppercase ASCII; empty key 0, 0 → 1. */
    fun beaconShortId(key: String): Int {
        if (key.isEmpty()) return 0
        var crc = 0xFFFF
        for (ch in key.uppercase()) {
            crc = crc xor ((ch.code and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return nonZero(crc)
    }

    /** 0 means none, so a real value of 0 is moved to 1. */
    fun nonZero(v: Int): Int = if (v == 0) 1 else v
}
