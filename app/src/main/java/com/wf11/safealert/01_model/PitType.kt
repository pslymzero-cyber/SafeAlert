package com.wf11.safealert.model

import com.wf11.safealert.ble.BleConstants

/**
 * PIT (Powered Industrial Truck) equipment type.
 *
 * Replaces free-text display names. Removing the input method itself structurally prevents people's names and
 * nicknames from reaching BLE advertisements and Firebase alert logs. On site, users only pick a type and a
 * number; no keyboard is used.
 *
 * The advertised ID is two tokens, `code-number`: `CB-01`, `RT-07`, `HR-99`.
 *   · Fixed 5 bytes: well inside the 15-byte ID limit (12 bytes during a rescue request)
 *   · The center name is not included. Devices only meet within the same physical center,
 *     and center names may be up to 12 characters, so including one would exceed the budget.
 *     Firebase logs prepend the center name at save time, e.g. `WF11-CB-01`.
 *
 * [category] sets the alert radius. Users do not pick a role separately: **choosing the equipment brings the
 * role with it**, so someone who picked a forklift can never run with the EPJ radius.
 *
 * **Category is assigned by metal cabin shielding and travel speed, not by equipment size.**
 * Judgment is RSSI-based, and these two dominate signal attenuation (`DevSettings.epjVsEpjBiasDb` comment:
 * "EPJs have no metal cabin, so shielding is weak, and at a low 3km/h, coexisting at 5m is normal").
 * Fast equipment with a cabin is `CAT_FORKLIFT` (strong shielding, conservative correction +8); slow equipment
 * without a cabin is `CAT_EPJ` (weak shielding -2, alerts only on entering 3m). Mast presence and load height
 * do not affect RSSI, so they are not assignment criteria; that is why the walkie (walk-behind stacker) is
 * `CAT_EPJ`.
 *
 * [code] is sent over BLE, so **never change it**: it would no longer match devices deployed in the field.
 * Only append new equipment below (order = order shown in the selection popup).
 */
enum class PitType(val code: String, val label: String, val category: Int) {
    COUNTER_BALANCE("CB", "Counterbalance", BleConstants.CAT_FORKLIFT),
    REACH          ("RT", "Reach Truck", BleConstants.CAT_FORKLIFT),
    HIGH_REACH     ("HR", "High Reach", BleConstants.CAT_FORKLIFT),
    ORDER_PICKER   ("OP", "Order Picker", BleConstants.CAT_FORKLIFT),
    STACKER        ("ST", "Stacker", BleConstants.CAT_FORKLIFT),
    TOW_TRACTOR    ("TT", "Tow Tractor", BleConstants.CAT_FORKLIFT),
    EPJ            ("EP", "Electric Pallet Jack", BleConstants.CAT_EPJ),
    WALKIE         ("WK", "Walkie Stacker", BleConstants.CAT_EPJ);

    companion object {
        /**
         * Equipment number range, used as-is by the popup dropdown.
         * The upper bound 99 was confirmed on site: a center never exceeds 100 units of the same type.
         * Fixed at 2 digits so the advertised ID fits in 5 bytes. Going to 3 digits would break the BLE notation and the
         * parsing on devices already deployed, so changing the bound requires a simultaneous rollout to every device.
         */
        const val NO_MIN = 1
        const val NO_MAX = 99

        fun fromCode(code: String): PitType? = PitType.values().firstOrNull { it.code == code }

        /** `CB-01`: the number is always 2 digits. A varying digit count breaks alignment on screen. */
        fun buildId(type: PitType, no: Int): String = "${type.code}-%02d".format(no)

        /**
         * Parses an advertised ID back into (type, number); used to restore the last selection when the popup reopens.
         * Returns null for an unregistered code even if the format matches, filtering out values left by older versions.
         */
        fun parse(id: String): Pair<PitType, Int>? {
            val m = Regex("^([A-Z]{2})-([0-9]{2})$").find(id.trim().uppercase()) ?: return null
            val type = fromCode(m.groupValues[1]) ?: return null
            val no = m.groupValues[2].toInt()
            if (no !in NO_MIN..NO_MAX) return null
            return type to no
        }
    }
}
