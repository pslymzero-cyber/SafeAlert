package com.wf11.safealert.utils

/**
 * Floor / process scope inside a site (e.g. WF11-1F-OB). Pure helpers, no Android imports.
 * Applies to server SOS reception only: an SOS heard over Bluetooth never goes through [receives].
 */
object SiteScope {
    /** Same text as the floor/proc validation in database.rules.json (pinned by DatabaseRulesParityTest). */
    const val CODE_PATTERN = "^[A-Z0-9]{1,4}\$"
    private val CODE = Regex(CODE_PATTERN)

    /** Trimmed, uppercased value if it is a valid floor/process code, else "". */
    fun code(raw: String?): String = raw?.trim()?.uppercase()?.takeIf { CODE.matches(it) }.orEmpty()

    /** Comma list ("1F,2F") to distinct valid codes; anything that is not a string gives []. */
    fun codes(raw: Any?): List<String> =
        (raw as? String)?.split(',')?.map { code(it) }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** Site node ({root}/sites/{sc}) to (floors, procs); a non-map node gives two empty lists. */
    fun lists(node: Any?): Pair<List<String>, List<String>> {
        val m = node as? Map<*, *> ?: return emptyList<String>() to emptyList()
        return codes(m["floors"]) to codes(m["procs"])
    }

    /** "WF11-1F-OB"; empty floor/proc parts are left out. */
    fun label(site: String, floor: String, proc: String): String =
        listOf(site, floor, proc).filter { it.isNotEmpty() }.joinToString("-")

    /** Record fields for floor/proc; empty or invalid values are omitted. */
    fun fields(floor: String, proc: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        code(floor).takeIf { it.isNotEmpty() }?.let { m["floor"] = it }
        code(proc).takeIf { it.isNotEmpty() }?.let { m["proc"] = it }
        return m
    }

    /**
     * Whether a server SOS record should ring here. Resolved records and the whole-site switch always pass; otherwise
     * floor and process each match when either side is empty or both are equal.
     */
    fun receives(active: Boolean, recFloor: String, recProc: String, myFloor: String, myProc: String, all: Boolean): Boolean {
        if (!active || all) return true
        fun match(a: String, b: String) = a.isEmpty() || b.isEmpty() || a == b
        return match(recFloor, myFloor) && match(recProc, myProc)
    }
}
