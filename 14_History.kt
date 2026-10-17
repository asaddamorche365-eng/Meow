package com.zenpx.zagent

import android.content.Context
import org.json.JSONObject
import java.io.File

class Entry(
    val time: Long,
    val status: String,   // REPLIED, SKIPPED, FAILED
    val handle: String,
    val id: String,
    val text: String,
    val reply: String,
    val reason: String,
    val kw: Boolean       // post matched a keyword
)

/** Saved list of what the agent did and why. Survives app restarts. */
class History(ctx: Context) {
    private val file = File(ctx.filesDir, "history.jsonl")
    val items = ArrayList<Entry>()

    init {
        if (file.exists()) {
            val lines = file.readLines().takeLast(500)
            for (l in lines) {
                try {
                    val o = JSONObject(l)
                    items.add(Entry(o.getLong("t"), o.getString("s"), o.optString("h"), o.optString("i"),
                        o.optString("x"), o.optString("r"), o.optString("w"), o.optBoolean("k")))
                } catch (_: Exception) {}
            }
            file.writeText(lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
        }
    }

    fun add(e: Entry) {
        items.add(e)
        if (items.size > 500) items.removeAt(0)
        val o = JSONObject().put("t", e.time).put("s", e.status).put("h", e.handle).put("i", e.id)
            .put("x", e.text).put("r", e.reply).put("w", e.reason).put("k", e.kw)
        file.appendText(o.toString() + "\n")
    }

    fun clear() {
        items.clear()
        file.delete()
    }

    fun count(status: String) = items.count { it.status == status }
}
