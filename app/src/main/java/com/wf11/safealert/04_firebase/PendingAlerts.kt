package com.wf11.safealert.firebase

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * (v1.1.98) 로그인 전 경보 기록 보류 — DB 규칙이 uid 를 필수로 요구해서, 익명 로그인 전
 * (첫 설치 직후 오프라인 등)에 생긴 기록은 uid 없이 보낼 수 없다. 여기 두었다가 로그인되면 보낸다.
 * SharedPreferences 에 JSON 배열로 두어 앱이 재시작돼도 남고, [max] 를 넘으면 오래된 것부터 버린다.
 */
internal class PendingAlerts(private val prefs: SharedPreferences, private val max: Int = 500) {

    /** [url] = 저장할 자리(DatabaseReference 전체 주소), [data] = uid 를 뺀 기록 */
    fun add(url: String, data: Map<String, Any>) {
        val arr = load()
        arr.put(JSONObject().put("url", url).put("data", JSONObject(data)))
        while (arr.length() > max) arr.remove(0)
        prefs.edit().putString(KEY, arr.toString()).commit()   // 동기 — 곧바로 앱이 꺼져도 남게
    }

    /** 보류분을 원래 순서대로 모두 꺼내고 비운다. */
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
