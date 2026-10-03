package com.wf11.safealert.ble

import com.wf11.safealert.utils.DevSettings

object BleConstants {
    const val SERVICE_UUID         = "00001234-0000-1000-8000-00805F9B34FB"
    const val DEVICE_PREFIX        = "SAFEALERT_DEVICE_"
    const val WALKER_PREFIX        = "SAFEALERT_WALKER_"

    // Device vs. Walker is told apart by CompanyID, saving advertising packet space
    const val COMPANY_ID_DEVICE   = 0x1234  // Equipment operator
    const val COMPANY_ID_WALKER   = 0x5678  // Pedestrian
    // For UWB address exchange (scan response only, separate from the main advertising packet)
    // Format: DEVICE (controller) 4 bytes = [addr0][addr1][channel][preambleIndex]
    //         / WALKER (controlee) 2 bytes = [addr0][addr1]
    const val COMPANY_ID_UWB_EXT  = 0x9ABC
    // For mutual RSSI exchange: carried in the scan response next to UWB_EXT (the main advertising packet is full).
    //   Each device echoes back, per peer, 'the RSSI I heard from you', so both sides judge with the same
    //   sym=(rssi_A→B + rssi_B→A)/2, removing per-phone TX/RX asymmetry (my phone silent while the peer alarms).
    //   Entry = [hash_hi][hash_lo][rssi(signed)], 3 bytes. hash = shortHash(peer fullId), 2 bytes.
    const val COMPANY_ID_RSSI_ECHO = 0xE0C0
    const val ECHO_ENTRY_SIZE      = 3        // Bytes per entry (2-byte hash + 1-byte signed RSSI)
    const val NO_ECHO_RSSI         = Int.MIN_VALUE   // No-echo sentinel (RSSI is negative dBm, so MIN_VALUE is safe)

    // ServiceData 2nd (extension) byte, bit0: zone beacon contact declaration.
    //   The 1-byte state (2-2-2-2) is full, so ServiceData gets one extra extension byte.
    //   Older receivers read only byte[0], so it is harmless (backward compatible);
    //   older senders lack byte[1], which reads as false.
    const val EXT_FLAG_IN_ZONE     = 0x01
    // Extension byte bit1: lone-worker rescue request. bit0 keeps its meaning; older versions read only byte[0] or
    //   bit0, so it is harmless
    const val EXT_FLAG_SOS         = 0x02

    // RSSI >= threshold → alert (the closer the device, the nearer RSSI gets to 0)
    // Warning: from farther away (more negative); danger: closer (less negative).
    // Thresholds are dBm values saved directly from the sliders; there is no distance conversion.
    //   The single source of the default thresholds is DevSettings.DEFAULT_RSSI_*_ABS (-78/-65). The constants below
    //   are only the runCatching fallback before DevSettings is initialized, so they are kept at the same values.
    const val DEFAULT_RSSI_WARNING       = -78
    const val DEFAULT_RSSI_DANGER        = -65
    // 1000ms: BleScanner.mapScanMode maps ≤1000ms to LOW_LATENCY (continuous scanning), removing the detection
    //   blind window (prevents late or missed alarms). Matches the DevSettings.scanPeriodMs default.
    const val DEFAULT_SCAN_PERIOD_MS     = 1000L
    const val DEFAULT_ADVERTISE_INTERVAL = 200

    val rssiWarning: Int       get() = runCatching { DevSettings.rssiWarning }.getOrDefault(DEFAULT_RSSI_WARNING)
    val rssiDanger: Int        get() = runCatching { DevSettings.rssiDanger }.getOrDefault(DEFAULT_RSSI_DANGER)
    val scanPeriodMs: Long     get() = runCatching { DevSettings.scanPeriodMs }.getOrDefault(DEFAULT_SCAN_PERIOD_MS)
    val advertiseInterval: Int get() = runCatching { DevSettings.advertiseInterval }.getOrDefault(DEFAULT_ADVERTISE_INTERVAL)

    const val LEVEL_SAFE    = 0
    const val LEVEL_WARNING = 1
    const val LEVEL_DANGER  = 2

    // IMU motion state codes (sender-internal representation only).
    //   Match the values returned by ImuFusion.motionState 1:1.
    //   0x00 stationary / 0x01 normal movement / 0x02 hard stop or sharp turn
    //   ※ These values are not put on the wire as-is: encodePayload() below maps them to the 2-bit STATE field
    //     (PSTATE_*) and packs everything into one byte.
    const val MOTION_STATE_STATIONARY = 0x00
    const val MOTION_STATE_NORMAL     = 0x01
    const val MOTION_STATE_SUDDEN     = 0x02

    // Threshold for immediate DANGER escalation on special states (reverse PSTATE_REVERSE / loading PSTATE_LOADING)
    //   = the danger threshold: the AlertStateMachine special-alert branch requires both pEma and the raw 1s
    //   average to reach effDanger (rssiDanger, default -65, minus the total offset). A device already alerting
    //   goes to DANGER at once; a first detection still needs the normal confirmation (waiver, 2-frame proximity
    //   or a sustained Time-Gate approach).

    // ───────────────────────────────────────────────────────────────
    // [Dynamic payload: 1-byte bit-packing protocol (2-2-2-2 split)]
    //   Packs/unpacks ServiceData byte0 as 4 fields (byte1 = extension flags, see encodeExt).
    //
    //   Bit:  7  6 | 5  4 | 3  2 | 1  0
    //         [ CAT ]|[STATE]|[TURN]|[RISK]
    //          2bit    2bit    2bit   2bit
    //
    //   CAT  (Category, sender role: bits 7:6):
    //     00 walker (WALKER) / 01 EPJ / 10 forklift, reach truck, order picker (FORKLIFT) / 11 reserved
    //   STATE (sender dynamic state: bits 5:4):
    //     00 stopped/normal (IDLE)      - stopped or ordinary (not a special alert)
    //     01 forward/driving (FORWARD)  - normal driving (not a special alert)
    //     10 reverse (REVERSE)          - triggers a special alert
    //     11 loading/working (LOADING)  - triggers a special alert / for forklifts, 'work at height'
    //   TURN (turn direction: bits 3:2):
    //     00 straight (STRAIGHT) / 01 left turn (LEFT) / 10 right turn (RIGHT) / 11 reserved
    //          Sent in real time by the sender (ImuFusion.turnDirection, based on the GAME_ROTATION_VECTOR
    //          azimuth derivative) → the receiver shows it in list/alert text only (not used for the level).
    //   RISK (bits 1:0):
    //     00 safe (nothing detected) / 01 warning detected / 10 danger detected / 11 reserved
    //          The sender transmits its own highest alertState level (BleService) →
    //          the receiver decodes it with decodeRisk and combines it with its own RSSI gate (a compromise)
    //          to escalate its alert.
    //          → If either side detects first, both sides alarm together: two-way cooperative alerting (fail-safe).
    //
    //   ※ Compatibility: walker at rest with nothing detected (CAT=00,STATE=00,TURN=00,RISK=00) = 0x00 →
    //     naturally matches the default 0x00 of iBeacon/MAC beacons that carry no payload (a safe default).
    // ───────────────────────────────────────────────────────────────

    // Category (bits 7:6)
    const val CAT_WALKER   = 0b00   // Pedestrian
    const val CAT_EPJ      = 0b01   // EPJ (electric pallet jack)
    const val CAT_FORKLIFT = 0b10   // Forklift, reach truck, order picker
    const val CAT_RESERVED = 0b11   // Reserved

    // State (bits 5:4): vehicle driving mode
    const val PSTATE_IDLE    = 0b00   // Stopped/normal (stopped or ordinary)
    const val PSTATE_FORWARD = 0b01   // Forward/driving (normal driving, not a special alert)
    const val PSTATE_REVERSE = 0b10   // Reverse (triggers a special alert)
    const val PSTATE_LOADING = 0b11   // Loading/working (special alert / forklift work at height)

    // Turn (bits 3:2): turn direction.
    const val TURN_STRAIGHT = 0b00   // Straight
    const val TURN_LEFT     = 0b01   // Left turn
    const val TURN_RIGHT    = 0b10   // Right turn
    const val TURN_RESERVED = 0b11   // Reserved

    // Bit-field masks/shifts (2-2-2-2: CAT high → STATE → TURN → RISK low)
    private const val CAT_SHIFT   = 6
    private const val CAT_MASK    = 0b11
    private const val STATE_SHIFT = 4
    private const val STATE_MASK  = 0b11
    private const val TURN_SHIFT  = 2
    private const val TURN_MASK   = 0b11
    // RISK (detected danger state): bits[1:0]. Carries LEVEL_* (0~2) as-is in 2 bits.
    private const val RISK_SHIFT  = 0
    private const val RISK_MASK   = 0b11

    /**
     * Packs 4 fields (Category 2bit + State 2bit + Turn 2bit + Risk 2bit) into one byte (2-2-2-2 split).
     * Layout: bits[7:6]=CAT, bits[5:4]=STATE, bits[3:2]=TURN, bits[1:0]=RISK.
     * @param category CAT_* (0~3); out-of-range upper bits are masked off.
     * @param state    PSTATE_* (0~3)
     * @param turn     TURN_* (0~3). The sender transmits ImuFusion.turnDirection (azimuth derivative).
     * @param risk     LEVEL_* (0~2). Sender's detected danger state (default SAFE); receivers use it to escalate.
     */
    fun encodePayload(category: Int, state: Int, turn: Int = TURN_STRAIGHT, risk: Int = LEVEL_SAFE): Byte {
        val c = (category and CAT_MASK) shl CAT_SHIFT
        val s = (state and STATE_MASK) shl STATE_SHIFT
        val t = (turn and TURN_MASK) shl TURN_SHIFT
        val r = (risk and RISK_MASK) shl RISK_SHIFT      // Detected danger state
        return (c or s or t or r).toByte()
    }

    /** Builds the extension byte: bit0=IN_ZONE, bit1=SOS. Independent of the first byte (encodePayload). */
    fun encodeExt(inZone: Boolean, sos: Boolean): Int =
        (if (inZone) EXT_FLAG_IN_ZONE else 0) or (if (sos) EXT_FLAG_SOS else 0)

    /** Extracts SOS (bit1) from the extension byte. A missing byte arrives as 0, so false. */
    fun decodeSos(ext: Int): Boolean = (ext and EXT_FLAG_SOS) != 0

    /** Extracts Category (bits 7:6) from the packed byte. */
    fun decodeCategory(payload: Int): Int = ((payload and 0xFF) shr CAT_SHIFT) and CAT_MASK

    /** Role code → name for aggregation; unknown (null/out of range) = UNKNOWN. Used for the alert record role field. */
    fun categoryName(cat: Int?): String = when (cat) {
        CAT_WALKER   -> "WALKER"
        CAT_EPJ      -> "EPJ"
        CAT_FORKLIFT -> "FORKLIFT"
        else         -> "UNKNOWN"
    }

    /** Extracts State (bits 5:4) from the packed byte. */
    fun decodeState(payload: Int): Int = ((payload and 0xFF) shr STATE_SHIFT) and STATE_MASK

    /** Extracts the Turn code (bits 3:2, TURN_*) from the packed byte. */
    fun decodeTurn(payload: Int): Int = ((payload and 0xFF) shr TURN_SHIFT) and TURN_MASK

    /** Extracts Risk (bits 1:0, LEVEL_*) from the packed byte: sender's detected danger (0 SAFE/1 warning/2 danger). */
    fun decodeRisk(payload: Int): Int = ((payload and 0xFF) shr RISK_SHIFT) and RISK_MASK

    // ── Korean display labels (single source shared by the Local and Target UIs) ──
    /** Category (CAT_*) -> Korean display label. */
    fun categoryLabel(category: Int): String = when (category) {
        CAT_WALKER   -> "보행자"
        CAT_EPJ      -> "EPJ"
        CAT_FORKLIFT -> "지게차"
        else         -> "예비"
    }

    /** State (PSTATE_*) -> Korean display label ("정지·일반" / "전진·주행" / "후진" / "하역·작업"). */
    fun stateLabel(state: Int): String = when (state) {
        PSTATE_IDLE    -> "정지·일반"
        PSTATE_FORWARD -> "전진·주행"
        PSTATE_REVERSE -> "후진"
        PSTATE_LOADING -> "하역·작업"
        else           -> "정지·일반"
    }

    /** Turn (TURN_*) -> Korean display label. */
    fun turnLabel(turn: Int): String = when (turn) {
        TURN_LEFT     -> "좌회전"
        TURN_RIGHT    -> "우회전"
        TURN_STRAIGHT -> "직진"
        else          -> "-"
    }

    // ── Mutual RSSI exchange: echo table encoding/decoding + short hash ──────────────
    /**
     * Reduces a peer fullId (prefix + wire id, same as the deviceRssiMap key) to a 2-byte (0..65535) hash.
     *   FNV-1a 32-bit, then XOR-fold the high and low halves → 16 bits. The sender calls it with the peer's fullId,
     *   the receiver with its own fullId, to find its own echo entry (same string on both sides → same hash).
     */
    fun shortHash(id: String): Int {
        var h = -0x7ee3623b               // 0x811C9DC5 FNV-1a offset basis (Int bit pattern)
        for (b in id.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193               // FNV prime
        }
        return (h xor (h ushr 16)) and 0xFFFF
    }

    /**
     * Encodes a list of echo entries ((hash, rssiDbm)) into a byte array, at most maxEntries entries.
     *   Each entry is 3 bytes: [hash>>8][hash&0xFF][rssi.coerceIn(-128,127)].
     */
    fun encodeEchoTable(entries: List<Pair<Int, Int>>, maxEntries: Int): ByteArray {
        val n = minOf(entries.size, maxEntries)
        val out = ByteArray(n * ECHO_ENTRY_SIZE)
        for (i in 0 until n) {
            val (hash, rssi) = entries[i]
            out[i * 3]     = ((hash ushr 8) and 0xFF).toByte()
            out[i * 3 + 1] = (hash and 0xFF).toByte()
            out[i * 3 + 2] = rssi.coerceIn(-128, 127).toByte()
        }
        return out
    }

    /**
     * Returns the RSSI (dBm) of the echo entry matching myHash, or null if none.
     *   The receiver finds its own echo by its own hash, recovering how strongly the peer heard it.
     */
    fun findEchoRssi(echoData: ByteArray, myHash: Int): Int? {
        var i = 0
        while (i + ECHO_ENTRY_SIZE <= echoData.size) {
            val hash = ((echoData[i].toInt() and 0xFF) shl 8) or (echoData[i + 1].toInt() and 0xFF)
            if (hash == myHash) return echoData[i + 2].toInt()
            i += ECHO_ENTRY_SIZE
        }
        return null
    }
}

/**
 * Own-device (Local) transmit state: the role/state/speed this device broadcasts over BLE.
 *   A model fully separate from the receive side (Target): the data models are kept apart so a decoded peer
 *   payload (TargetState) can never overwrite these values (stops received hex from polluting the local UI).
 */
data class LocalState(
    val category: Int  = BleConstants.CAT_WALKER,       // My role (CAT_*)
    val state: Int     = BleConstants.PSTATE_IDLE,       // My dynamic state (PSTATE_*)
    val turnDir: Int   = BleConstants.TURN_STRAIGHT,     // My transmitted turn direction (TURN_*)
    val inZone: Boolean = false                          // In a safe zone (default false: backward compatible with the old format)
) {
    val categoryLabel: String get() = BleConstants.categoryLabel(category)
    val stateLabel: String    get() = BleConstants.stateLabel(state)
    val turnLabel: String     get() = BleConstants.turnLabel(turnDir)
}

/**
 * Received target (Target) state: decoded 1-byte payload broadcast by a peer device.
 *   One per deviceId. Never affects the own-device (Local) display.
 */
data class TargetState(
    val deviceId: String,
    val displayName: String,
    val category: Int,        // Peer role (CAT_*)
    val state: Int,           // Peer dynamic state (PSTATE_*)
    val turnDir: Int,         // Peer transmitted turn direction (TURN_*)
    val level: Int,           // Alert level (LEVEL_*)
    val rssi: Int             // Latest RSSI (dBm)
) {
    val categoryLabel: String get() = BleConstants.categoryLabel(category)
    val stateLabel: String    get() = BleConstants.stateLabel(state)
    val turnLabel: String     get() = BleConstants.turnLabel(turnDir)
}
