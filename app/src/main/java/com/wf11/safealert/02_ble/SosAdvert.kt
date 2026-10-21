package com.wf11.safealert.ble

import com.wf11.safealert.utils.SiteScope
import com.wf11.safealert.utils.WorkerCode

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
 *
 * Scan response: while an SOS is active it carries one manufacturer data (company ID 0x5053) = SOS ID 8 bytes + scope
 * byte 1 + zone 4 + worker code 10 = 23 bytes, so 1+1+2+23 = 27 <= 31 bytes. 1.2.13 receivers read only the first
 * 9 bytes (INFO_LEN). It replaces the UWB address and RSSI echo during the SOS (the SOS phone judges no proximity).
 * The main advertising packet is not touched, so older receivers are unaffected.
 */
object SosAdvert {
    const val ID_MAX = 15
    const val EXT_ID_MAX = 12
    const val PACKET_MAX = 31

    /** SOS ID text: 16 lowercase hex chars (SecureRandom 8 bytes). Same text as the sosId rule in database.rules.json. */
    const val SOS_ID_PATTERN = "^[0-9a-f]{16}\$"
    private val SOS_ID = Regex(SOS_ID_PATTERN)

    /** The part of the SOS info that 1.2.13 also reads: SOS ID 8 bytes + scope byte 1. */
    const val INFO_LEN = 9

    /** Zone ASCII at 9..12 (0-padded), worker code ASCII at 13..22 (0-padded); the full info is 23 bytes. */
    const val ZONE_AT = 9
    const val ZONE_MAX = 4
    const val WORKER_AT = 13
    const val WORKER_MAX = WorkerCode.MAX_LEN
    const val INFO_FULL_LEN = 23

    fun isSosId(s: String): Boolean = SOS_ID.matches(s)

    /**
     * Scope byte: high 4 bits = floor (SiteScope.FLOORS position + 1), low 4 bits = process (SiteScope.PROCS
     * position + 1); 0 = none or not in the list.
     */
    fun scopeByte(floor: String, proc: String): Int =
        ((SiteScope.FLOORS.indexOf(floor) + 1) shl 4) or (SiteScope.PROCS.indexOf(proc) + 1)

    fun floorOf(scope: Int): String = SiteScope.FLOORS.getOrNull(((scope shr 4) and 0x0F) - 1).orEmpty()

    fun procOf(scope: Int): String = SiteScope.PROCS.getOrNull((scope and 0x0F) - 1).orEmpty()

    /**
     * SOS info payload (SOS ID 8 bytes + scope byte + zone 4 + worker code 10, 23 bytes); null if the SOS ID is not
     * valid. A zone or worker code that does not match its format is left as zeros.
     */
    fun info(sosId: String, scope: Int, zone: String = "", worker: String = ""): ByteArray? {
        if (!isSosId(sosId)) return null
        val out = ByteArray(INFO_FULL_LEN)
        for (i in 0 until 8) out[i] = sosId.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        out[8] = scope.toByte()
        SiteScope.code(zone).toByteArray(Charsets.US_ASCII).copyInto(out, ZONE_AT)
        WorkerCode.code(worker).toByteArray(Charsets.US_ASCII).copyInto(out, WORKER_AT)
        return out
    }

    /** SOS ID from the info payload as lowercase hex; "" if absent or too short. */
    fun decodeSosId(info: ByteArray?): String =
        if (info != null && info.size >= INFO_LEN) (0 until 8).joinToString("") { "%02x".format(info[it].toInt() and 0xFF) } else ""

    /** Scope byte from the info payload, unsigned; 0 if absent. */
    fun decodeScope(info: ByteArray?): Int =
        if (info != null && info.size >= INFO_LEN) info[8].toInt() and 0xFF else 0

    private fun asciiAt(info: ByteArray, at: Int, max: Int): String {
        var n = 0
        while (n < max && info[at + n].toInt() != 0) n++
        return String(info, at, n, Charsets.US_ASCII)
    }

    /** Zone from the info payload; "" if absent (older sender), too short or not in the zone format. */
    fun decodeZone(info: ByteArray?): String =
        if (info != null && info.size >= ZONE_AT + ZONE_MAX) SiteScope.code(asciiAt(info, ZONE_AT, ZONE_MAX)) else ""

    /** Worker code from the info payload; "" if absent (older sender), too short or not in the worker code format. */
    fun decodeWorker(info: ByteArray?): String =
        if (info != null && info.size >= INFO_FULL_LEN) WorkerCode.code(asciiAt(info, WORKER_AT, WORKER_MAX)) else ""

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
