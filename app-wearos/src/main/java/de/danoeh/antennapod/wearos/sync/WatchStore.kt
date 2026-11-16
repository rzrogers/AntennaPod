package de.danoeh.antennapod.wearos.sync

import android.content.Context
import android.content.SharedPreferences
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object WatchStore {
    const val AMOUNT_OFF = 0
    const val AMOUNT_ALL = -1
    const val AMOUNT_DEFAULT = 60
    val AMOUNTS = listOf(AMOUNT_OFF, 60, 120, 180, 300, AMOUNT_ALL)
    private const val PREFS = "plus_watch"
    private const val KEY_AMOUNT = "amount_"
    private const val KEY_LTE = "use_lte"
    private const val KEY_RECORD = "record"
    private const val KEY_SPEEDS = "speeds"
    private const val KEY_BACK = "back"
    private const val KEY_FORWARD = "forward"
    private const val KEY_FOLLOW = "follow"
    private const val KEY_LAST_ITEM = "last_item"
    private const val KEY_LAST_PLAYLIST = "last_playlist"
    private const val UNKNOWN_DURATION_MILLIS = 30 * 60_000L

    private val plans = HashMap<Long, PlusWear.WatchPlan>()
    private var plansLoaded = false

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun planDirectory(context: Context): File = File(context.filesDir, "watch_plans").apply { mkdirs() }

    fun amount(context: Context, playlistId: Long): Int =
        prefs(context).getInt(KEY_AMOUNT + playlistId, AMOUNT_DEFAULT)

    fun setAmount(context: Context, playlistId: Long, minutes: Int) {
        prefs(context).edit().putInt(KEY_AMOUNT + playlistId, minutes).apply()
    }

    fun useLte(context: Context): Boolean = prefs(context).getBoolean(KEY_LTE, false)

    fun setUseLte(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LTE, value).apply()
    }

    fun lastItem(context: Context): Long = prefs(context).getLong(KEY_LAST_ITEM, 0L)

    fun lastPlaylist(context: Context): Long = prefs(context).getLong(KEY_LAST_PLAYLIST, 0L)

    fun setLast(context: Context, itemId: Long, playlistId: Long) {
        prefs(context).edit().putLong(KEY_LAST_ITEM, itemId).putLong(KEY_LAST_PLAYLIST, playlistId).apply()
    }

    fun followQueue(context: Context): Boolean = prefs(context).getBoolean(KEY_FOLLOW, true)

    fun speeds(context: Context): FloatArray {
        val stored = prefs(context).getString(KEY_SPEEDS, "") ?: ""
        return stored.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
    }

    fun jumpSeconds(context: Context): IntArray =
        intArrayOf(prefs(context).getInt(KEY_BACK, 10), prefs(context).getInt(KEY_FORWARD, 30))

    @Synchronized
    private fun ensurePlans(context: Context) {
        if (plansLoaded) {
            return
        }
        plansLoaded = true
        planDirectory(context).listFiles()?.forEach { file ->
            val plan = try {
                PlusWear.watchPlanFromBytes(file.readBytes())
            } catch (e: Exception) {
                null
            }
            if (plan != null) {
                plans[plan.id] = plan
            }
        }
    }

    @Synchronized
    private fun writePlan(context: Context, plan: PlusWear.WatchPlan) {
        plans[plan.id] = plan
        try {
            File(planDirectory(context), "${plan.id}.json").writeBytes(PlusWear.watchPlanToBytes(plan))
        } catch (e: Exception) {
            plans[plan.id] = plan
        }
    }

    @Synchronized
    fun savePlan(context: Context, data: ByteArray) {
        ensurePlans(context)
        val plan = PlusWear.watchPlanFromBytes(data) ?: return
        writePlan(context, plan)
    }

    @Synchronized
    fun plansDone(context: Context, data: ByteArray) {
        ensurePlans(context)
        try {
            val obj = JSONObject(String(data, Charsets.UTF_8))
            val ids = HashSet<Long>()
            val array = obj.optJSONArray("ids") ?: JSONArray()
            for (i in 0 until array.length()) {
                ids.add(array.optLong(i))
            }
            plans.keys.filter { it !in ids }.forEach { id ->
                plans.remove(id)
                File(planDirectory(context), "$id.json").delete()
            }
            val speedArray = obj.optJSONArray("speeds") ?: JSONArray()
            val speedList = ArrayList<String>()
            for (i in 0 until speedArray.length()) {
                speedList.add(speedArray.optDouble(i, 1.0).toFloat().toString())
            }
            prefs(context).edit()
                .putString(KEY_SPEEDS, speedList.joinToString(","))
                .putInt(KEY_BACK, obj.optInt("back", 10))
                .putInt(KEY_FORWARD, obj.optInt("forward", 30))
                .putBoolean(KEY_FOLLOW, obj.optBoolean("follow", true))
                .apply()
        } catch (e: JSONException) {
            return
        }
    }

    @Synchronized
    fun plan(context: Context, playlistId: Long): PlusWear.WatchPlan? {
        ensurePlans(context)
        return plans[playlistId]
    }

    @Synchronized
    fun allPlans(context: Context): List<PlusWear.WatchPlan> {
        ensurePlans(context)
        return plans.values.sortedWith(compareByDescending<PlusWear.WatchPlan> { it.loaded }.thenBy { it.id })
    }

    fun findMedia(context: Context, itemId: Long): PlusWear.WatchMedia? {
        for (plan in allPlans(context)) {
            plan.items.firstOrNull { it.itemId == itemId }?.let { return it }
        }
        return null
    }

    fun planContaining(context: Context, itemId: Long, preferred: Long): PlusWear.WatchPlan? {
        val all = allPlans(context)
        all.firstOrNull { it.id == preferred && it.items.any { item -> item.itemId == itemId } }?.let { return it }
        return all.firstOrNull { it.items.any { item -> item.itemId == itemId } }
    }

    @Synchronized
    fun removeItem(context: Context, itemId: Long) {
        ensurePlans(context)
        for (plan in plans.values.toList()) {
            if (plan.items.any { it.itemId == itemId }) {
                writePlan(context, PlusWear.WatchPlan(plan.id, plan.loaded, plan.items.filter { it.itemId != itemId }))
            }
        }
    }

    fun timeLeft(media: PlusWear.WatchMedia): Long {
        if (media.duration <= 0) {
            return UNKNOWN_DURATION_MILLIS
        }
        return (media.duration - media.position).coerceAtLeast(0).toLong()
    }

    fun wanted(plan: PlusWear.WatchPlan, minutes: Int): List<PlusWear.WatchMedia> {
        if (minutes == AMOUNT_OFF) {
            return emptyList()
        }
        if (minutes == AMOUNT_ALL) {
            return plan.items
        }
        val limit = minutes * 60_000L
        val result = ArrayList<PlusWear.WatchMedia>()
        var total = 0L
        for (media in plan.items) {
            if (total >= limit) {
                break
            }
            result.add(media)
            total += timeLeft(media)
        }
        return result
    }

    fun downloaded(context: Context, plan: PlusWear.WatchPlan): List<PlusWear.WatchMedia> =
        plan.items.filter { WatchFiles.has(context, it) }

    @Synchronized
    private fun readRecord(context: Context): JSONArray {
        val stored = prefs(context).getString(KEY_RECORD, null) ?: return JSONArray()
        return try {
            JSONArray(stored)
        } catch (e: JSONException) {
            JSONArray()
        }
    }

    @Synchronized
    fun record(context: Context, type: String, id: Long, position: Int = 0) {
        val events = readRecord(context)
        val kept = JSONArray()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val samePosition = type == "position" && event.optString("type") == "position" &&
                event.optLong("id") == id
            if (!samePosition) {
                kept.put(event)
            }
        }
        try {
            kept.put(
                JSONObject()
                    .put("type", type)
                    .put("id", id)
                    .put("position", position)
                    .put("time", System.currentTimeMillis())
            )
        } catch (e: JSONException) {
            return
        }
        prefs(context).edit().putString(KEY_RECORD, kept.toString()).apply()
    }

    @Synchronized
    fun recordedPosition(context: Context, itemId: Long): Int? {
        val events = readRecord(context)
        for (i in events.length() - 1 downTo 0) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("type") == "position" && event.optLong("id") == itemId) {
                return event.optInt("position", 0)
            }
        }
        return null
    }

    @Synchronized
    fun recordBytes(context: Context): ByteArray? {
        val events = readRecord(context)
        if (events.length() == 0) {
            return null
        }
        return try {
            JSONObject().put("events", events).toString().toByteArray(Charsets.UTF_8)
        } catch (e: JSONException) {
            null
        }
    }

    @Synchronized
    fun clearRecord(context: Context, upToTime: Long) {
        val events = readRecord(context)
        val kept = JSONArray()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optLong("time") > upToTime) {
                kept.put(event)
            }
        }
        prefs(context).edit().putString(KEY_RECORD, kept.toString()).apply()
    }

    @Synchronized
    fun recordCount(context: Context): Int = readRecord(context).length()
}
