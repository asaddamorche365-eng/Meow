package com.zenpx.zagent

import android.content.Context
import java.io.File

/** Remembers every post id that was handled, so nothing is replied to twice. */
class Store(ctx: Context) {
    private val file = File(ctx.filesDir, "done.txt")
    private val ids = HashSet<String>()

    init {
        if (file.exists()) file.forEachLine { if (it.isNotBlank()) ids.add(it.trim()) }
    }

    fun has(id: String) = ids.contains(id)

    fun add(id: String) {
        if (id.isNotBlank() && ids.add(id)) file.appendText(id + "\n")
    }

    fun size() = ids.size

    /** Pulls every 15-20 digit post id out of any text/CSV/JSON/links file. */
    fun importText(text: String): Int {
        var n = 0
        Regex("(?<![0-9])([0-9]{15,20})(?![0-9])").findAll(text).forEach {
            val id = it.groupValues[1]
            if (!ids.contains(id)) { add(id); n++ }
        }
        return n
    }
}
