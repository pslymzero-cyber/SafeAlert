package com.wf11.safealert.utils

/**
 * Floor / process / zone scope inside a site (e.g. WF11-1F-OB-Z1). Pure helpers, no Android imports.
 * Applies to server SOS reception only: an SOS heard over Bluetooth never goes through [receives].
 */
object SiteScope {
    /** Same text as the floor/proc validation in database.rules.json (pinned by DatabaseRulesParityTest). */
    const val CODE_PATTERN = "^[A-Z0-9]{1,4}\$"
    private val CODE = Regex(CODE_PATTERN)

    /** Trimmed, uppercased value if it is a valid floor/process code, else "". */
    fun code(raw: String?): String = raw?.trim()?.uppercase()?.takeIf { CODE.matches(it) }.orEmpty()

    /**
     * Picker choices built into the app. The order is carried in the Bluetooth SOS signal (SosAdvert.scopeByte), so
     * never reorder or remove entries; only append at the end.
     */
    val FLOORS: List<String> = (1..10).map { "${it}F" }
    val PROCS: List<String> = listOf("OB", "IB", "ICQA", "HUB", "EHS", "HR")

    /** "WF11-1F-OB-Z1"; empty floor/proc/zone parts are left out. */
    fun label(site: String, floor: String, proc: String, zone: String = ""): String =
        listOf(site, floor, proc, zone).filter { it.isNotEmpty() }.joinToString("-")

    /** Who my own SOS reaches, for the wording on screen and in notifications. */
    fun audience(floor: String, proc: String, zone: String = ""): String {
        val names = listOf(floor to "층", proc to "공정", zone to "존").filter { it.first.isNotEmpty() }.map { it.second }
        if (names.isEmpty()) return "같은 사업장"
        return "같은 ${names.joinToString("·")}(${label("", floor, proc, zone)})"
    }

    /** Record fields for floor/proc/zone; empty or invalid values are omitted. */
    fun fields(floor: String, proc: String, zone: String = ""): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        code(floor).takeIf { it.isNotEmpty() }?.let { m["floor"] = it }
        code(proc).takeIf { it.isNotEmpty() }?.let { m["proc"] = it }
        code(zone).takeIf { it.isNotEmpty() }?.let { m["zone"] = it }
        return m
    }

    /**
     * The map without floor/proc/zone, or null when it carries none. Rules that predate those fields refuse a record
     * carrying them, so a refused write goes again with this.
     */
    fun <V> withoutScope(m: Map<String, V>): Map<String, V>? =
        if ("floor" in m || "proc" in m || "zone" in m) m - "floor" - "proc" - "zone" else null

    /**
     * Whether a server SOS record should ring here. Resolved records and the whole-site switch always pass; otherwise
     * floor, process and zone each match when either side is empty or both are equal.
     */
    fun receives(
        active: Boolean, recFloor: String, recProc: String, myFloor: String, myProc: String, all: Boolean,
        recZone: String, myZone: String,
    ): Boolean {
        if (!active || all) return true
        fun match(a: String, b: String) = a.isEmpty() || b.isEmpty() || a == b
        return match(recFloor, myFloor) && match(recProc, myProc) && match(recZone, myZone)
    }
}
