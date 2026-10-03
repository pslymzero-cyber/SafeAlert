package com.wf11.safealert.firebase

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds alert records made before sign-in — the DB rules require uid, so records created before anonymous sign-in
 * (e.g. offline right after first install) cannot be sent without it. They wait here and are sent once signed in.
 * Kept as a JSON array in SharedPreferences so they survive app restarts; past [max], the oldest are dropped first.
 */
internal class PendingAlerts(private val prefs: SharedPreferences, private val max: Int = 500) {

    /** [url] = where to save (full DatabaseReference URL), [data] = the record without uid */
    fun add(url: String, data: Map<String, Any>) {
        val arr = load()
        arr.put(JSONObject().put("url", url).put("data", JSONObject(data)))
        while (arr.length() > max) arr.remove(0)
        prefs.edit().putString(KEY, arr.toString()).commit()   // Synchronous, so it survives even if the app is killed right after
    }

    /** Takes out all held records in their original order and clears the store. */
    fun drain(): List<Pair<String, Map<String, Any>>> {
        val arr = load()
        if (arr.length() == 0) return emptyList()
        prefs.edit().remove(KEY).commit()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val d = o.optJSONObject("data") ?: return@mapNotNull null
            val url = o.optString("url").ifEmpty { return@mapNotNull null }
            url to d.keys().asSequence().associateWith { k -> d.get(k) }
        }
    }

    fun size(): Int = load().length()

    private fun load(): JSONArray =
        runCatching { JSONArray(prefs.getString(KEY, null) ?: "[]") }.getOrElse { JSONArray() }

    private companion object {
        const val KEY = "items"
    }
}
