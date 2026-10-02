package slop.miho.aurora.data

import android.content.Context
import java.io.File
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** One day of tunnel traffic: every session's counters summed into the day's bucket. */
data class DailyTraffic(val epochDay: Long, val rx: Long, val tx: Long)

/**
 * The week's daily traffic totals, kept across runs so the stats screen's weekly chart
 * survives restarts and disconnected days. Only aggregate byte counts live here — no
 * addresses, no keys, nothing the panel ever said. A write a minute at most: deltas are
 * accumulated in memory and flushed to today's bucket as the session runs.
 */
object DailyTrafficStore {
    private const val FILE_NAME = "daily-traffic.json"
    private const val VERSION = 1

    /** The last seven days' buckets, oldest first; missing days simply aren't in the list. */
    fun readWeek(context: Context): List<DailyTraffic> {
        val text = runCatching { cacheFile(context).readText() }.getOrNull() ?: return emptyList()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        if (json.optInt("version") != VERSION) return emptyList()
        val raw = json.optJSONArray("days") ?: return emptyList()
        val firstDay = LocalDate.now().toEpochDay() - 6
        return buildList {
            for (index in 0 until raw.length()) {
                val day = raw.optJSONObject(index) ?: continue
                val bucket = DailyTraffic(day.optLong("day"), day.optLong("rx"), day.optLong("tx"))
                if (bucket.epochDay >= firstDay) add(bucket)
            }
        }.sortedBy { it.epochDay }
    }

    /** Adds [rx]/[tx] to today's bucket, keeps at most the last seven days, and returns the
     *  fresh week for the state flow. */
    fun addToday(context: Context, rx: Long, tx: Long): List<DailyTraffic> {
        if (rx < 0 || tx < 0) return readWeek(context)
        val today = LocalDate.now().toEpochDay()
        val days = readWeek(context).toMutableList()
        val index = days.indexOfFirst { it.epochDay == today }
        if (index >= 0) {
            val day = days[index]
            days[index] = day.copy(rx = day.rx + rx, tx = day.tx + tx)
        } else {
            days.add(DailyTraffic(today, rx, tx))
        }
        val week = days.sortedBy { it.epochDay }.takeLast(7)
        write(context, week)
        return week
    }

    private fun write(context: Context, week: List<DailyTraffic>) {
        val days = JSONArray()
        week.forEach { day ->
            days.put(
                JSONObject()
                    .put("day", day.epochDay)
                    .put("rx", day.rx)
                    .put("tx", day.tx),
            )
        }
        val json = JSONObject().put("version", VERSION).put("days", days)
        val target = cacheFile(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        runCatching {
            tmp.writeText(json.toString())
            check(tmp.renameTo(target)) { "Couldn't write daily traffic" }
        }
        // A failed write only costs the week's tail: the counters keep running, the next
        // flush tries again.
    }

    private fun cacheFile(context: Context): File = File(context.filesDir, FILE_NAME)
}
