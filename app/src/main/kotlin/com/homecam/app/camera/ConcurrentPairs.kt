package com.homecam.app.camera

import android.content.Context
import com.homecam.core.lens.LensEntry
import com.homecam.proto.LensPair
import com.homecam.proto.Resolution
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lens pairs a probe has shown can run at once, kept across restarts. The camera
 * advertises these and refuses any other second lens. A fresh install has none.
 */
object ConcurrentPairs {

    private const val PREFS = "homecam.lens"
    private const val KEY = "pairs"

    /** One set the probe ran, and the largest size at which it held. */
    data class Proven(val cameraIds: List<String>, val width: Int, val height: Int)

    fun read(context: Context): List<LensPair> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    /** Replaces whatever was stored. A run that proved nothing clears the previous pairs. */
    fun record(context: Context, lenses: List<LensEntry>, proven: List<Proven>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, encode(pairsOf(lenses, proven)))
            .commit()
    }

    fun pairsOf(lenses: List<LensEntry>, proven: List<Proven>): List<LensPair> {
        val best = linkedMapOf<Pair<String, String>, Proven>()
        for (set in proven) {
            val ids = lensIds(lenses, set.cameraIds) ?: continue
            val kept = best[ids]
            if (kept == null || set.area > kept.area) best[ids] = set
        }
        return best.map { (ids, set) -> pair(ids.first, ids.second, set.width, set.height) }
    }

    private val Proven.area: Long get() = width.toLong() * height

    private fun lensIds(lenses: List<LensEntry>, cameraIds: List<String>): Pair<String, String>? {
        if (cameraIds.size != 2) return null
        val mapped = cameraIds.map { cameraId ->
            // First match: the wide lens is listed before a zoom-ratio ultra-wide on the same camera.
            lenses.firstOrNull { it.cameraId == cameraId && it.physicalId == null }?.id ?: return null
        }
        if (mapped[0] == mapped[1]) return null
        return if (mapped[0] < mapped[1]) mapped[0] to mapped[1] else mapped[1] to mapped[0]
    }

    private fun pair(a: String, b: String, width: Int, height: Int): LensPair = LensPair.newBuilder()
        .setA(a)
        .setB(b)
        .setMax(Resolution.newBuilder().setWidth(width).setHeight(height))
        .build()

    private fun encode(pairs: List<LensPair>): String {
        val array = JSONArray()
        for (pair in pairs) {
            array.put(
                JSONObject()
                    .put("a", pair.a)
                    .put("b", pair.b)
                    .put("width", pair.max.width)
                    .put("height", pair.max.height),
            )
        }
        return array.toString()
    }

    private fun decode(raw: String): List<LensPair> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val a = obj.optString("a")
                val b = obj.optString("b")
                val width = obj.optInt("width")
                val height = obj.optInt("height")
                if (a.isEmpty() || b.isEmpty() || a == b || width <= 0 || height <= 0) continue
                add(pair(a, b, width, height))
            }
        }
    }
}
