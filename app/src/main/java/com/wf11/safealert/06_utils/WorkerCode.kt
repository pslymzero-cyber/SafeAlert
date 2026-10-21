package com.wf11.safealert.utils

import java.util.Locale

/**
 * Worker code: one letter plus up to nine digits (e.g. G6628767). It is the walker's Bluetooth ID and the worker field
 * of an SOS record. The same pattern lives in database.rules.json and both Apps Script files (pinned by parity tests).
 * Pure helpers, no Android imports.
 */
object WorkerCode {
    const val PATTERN = "^[A-Z][0-9]{1,9}\$"
    const val MAX_LEN = 10
    private val FORM = Regex(PATTERN)

    /** Trimmed, uppercased value if it is a valid worker code, else "". */
    fun code(raw: String?): String = raw?.trim()?.uppercase(Locale.ROOT)?.takeIf { FORM.matches(it) }.orEmpty()

    /**
     * The ID a monitoring session starts with: a walker advertises the worker code, a device advertises its equipment
     * number, and only when that is empty the fallback (an auto ID). A walker never advertises the equipment number
     * that happens to be saved (that used to leak into the walker ID).
     */
    fun startId(walker: Boolean, workerCode: String, equipment: String, fallback: () -> String): String {
        val id = if (walker) code(workerCode) else equipment.trim()
        return id.ifEmpty { fallback() }
    }
}
