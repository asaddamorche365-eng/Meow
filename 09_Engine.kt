package com.zenpx.zagent

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import kotlin.coroutines.resume

data class Cfg(
    val mode: String,                // "feed" or "search"
    val query: String,
    val like: Boolean,
    val rules: String,
    val maxWords: Int,
    val own: String,
    val keywords: List<String>,      // lowercase
    val onlyKeyword: Boolean,        // reply only to posts that contain a keyword
    val skipWords: List<String>,     // lowercase
    val minLen: Int,                 // minimum post text length
    val pause: Pair<Int, Int>,       // seconds after a normal reply
    val kwPause: Pair<Int, Int>,     // seconds after a keyword-post reply
    val maxReplies: Int              // 0 = no limit
)

/**
 * The loop. Script part (zb.js) reads the page and the app taps it with real touches.
 * The agent (offline model) only writes the reply text.
 */
class Engine(
    private val web: WebView,
    private val agent: Agent,
    private val store: Store,
    private val history: History,
    private val log: (String) -> Unit,
    private val onChange: () -> Unit
) {
    @Volatile var running = false
    var replies = 0
        private set
    /** Posts passed over by a filter (ad, own post, no keyword...). Counted, not listed in History. */
    val filteredBy = LinkedHashMap<String, Int>()

    private var job: Job? = null
    private val script: String by lazy {
        web.context.assets.open("zb.js").bufferedReader().use { it.readText() }
    }

    // ---- fixed pacing (milliseconds) ----
    private val modelSkipPause = 1500 to 3500
    private val breakEvery = 25
    private val breakPause = 120000 to 240000

    fun start(scope: CoroutineScope, cfg: Cfg, onStop: () -> Unit) {
        if (running) return
        running = true
        replies = 0
        job = scope.launch {
            try {
                loop(cfg)
            } catch (e: CancellationException) {
                // stopped by user
            } catch (e: Throwable) {
                log("Error: ${e.message}")
            } finally {
                running = false
                onStop()
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
    }

    private companion object {
        const val OK = 0
        const val SKIP = 1
        const val FAIL = 2
    }

    private fun rnd(a: Int, b: Int): Long = a + (Math.random() * (b - a)).toLong()
    private fun rnd(r: Pair<Int, Int>): Long = rnd(r.first, r.second)
    private fun secs(r: Pair<Int, Int>): Long = rnd(r.first * 1000, maxOf(r.first, r.second) * 1000)

    private fun addHist(status: String, p: JSONObject, reply: String, reason: String, kw: Boolean) {
        history.add(Entry(
            System.currentTimeMillis(), status, p.optString("handle"), p.optString("id"),
            p.optString("text").replace("\n", " ").trim().take(220), reply, reason, kw
        ))
        onChange()
    }

    private suspend fun loop(cfg: Cfg) {
        val startUrl = if (cfg.mode == "search")
            "https://x.com/search?q=" + URLEncoder.encode(cfg.query, "UTF-8") + "&src=typed_query&f=live"
        else "https://x.com/home"

        var own = cfg.own.trim().removePrefix("@")
        var errors = 0
        var empty = 0
        filteredBy.clear()

        open(startUrl)
        if (own.isEmpty()) own = (js("ZB.own()") as? String).orEmpty()
        if (own.isEmpty()) {
            log("Type your X username (no @) in the panel, then press Start.")
            return
        }

        while (running) {
            val st = js("ZB.state()") as? JSONObject
            when (st?.optString("err").orEmpty()) {
                "login" -> { log("Login needed. Log in to X in the browser, then press Start."); return }
                "automated" -> { log("X shows an automation warning. Stopped for safety."); return }
                "wrong" -> {
                    errors++
                    log("X error $errors/3")
                    if (errors >= 3) { log("3 errors. Stopped for safety, take a break."); return }
                    delay(20000)
                    open(startUrl)
                    continue
                }
            }

            val posts = (js("ZB.posts()") as? JSONArray) ?: JSONArray()
            val picked = pick(posts, cfg, own)
            if (picked == null) {
                empty++
                if (empty >= 6) {
                    log("No new posts, refreshing.")
                    empty = 0
                    open(startUrl)
                } else {
                    scrollDown()
                }
                continue
            }
            empty = 0
            val p = picked.first
            val kw = picked.second

            when (handle(p, kw, cfg)) {
                OK -> {
                    replies++
                    log("Replied. Total this run: $replies")
                    if (cfg.maxReplies > 0 && replies >= cfg.maxReplies) {
                        log("Reached $replies replies. Stopped."); return
                    }
                    if (replies % breakEvery == 0) {
                        log("Taking a long break.")
                        delay(rnd(breakPause))
                    } else {
                        delay(secs(if (kw) cfg.kwPause else cfg.pause))
                    }
                    scrollDown()
                }
                SKIP -> {
                    delay(rnd(modelSkipPause))
                    scrollDown()
                }
                else -> {
                    errors++
                    log("Reply failed ($errors/3).")
                    if (errors >= 3) { log("3 errors. Stopped for safety, take a break."); return }
                    delay(5000)
                    open(startUrl)
                }
            }
        }
    }

    private fun filtered(reason: String) {
        filteredBy[reason] = (filteredBy[reason] ?: 0) + 1
        onChange()
    }

    /** First post that passes every filter. Returns the post and whether it matched a keyword. */
    private fun pick(posts: JSONArray, cfg: Cfg, own: String): Pair<JSONObject, Boolean>? {
        for (i in 0 until posts.length()) {
            val p = posts.optJSONObject(i) ?: continue
            val id = p.optString("id")
            if (id.isEmpty() || store.has(id)) continue
            val text = p.optString("text").trim()
            val low = text.lowercase()
            val kw = cfg.keywords.any { low.contains(it) }
            when {
                p.optBoolean("ad") -> { store.add(id); filtered("ad"); continue }
                p.optString("handle").equals(own, true) -> { store.add(id); filtered("own post"); continue }
                cfg.onlyKeyword && !kw -> { store.add(id); filtered("no keyword"); continue }
                text.length < cfg.minLen -> {
                    store.add(id)
                    addHist("SKIPPED", p, "", "post too short (${text.length} chars)", kw)
                    continue
                }
                cfg.skipWords.any { low.contains(it) } -> {
                    store.add(id)
                    addHist("SKIPPED", p, "", "contains a skip word", kw)
                    continue
                }
            }
            return Pair(p, kw)
        }
        return null
    }

    private fun fail(p: JSONObject, comment: String, kw: Boolean, why: String): Int {
        addHist("FAILED", p, comment, why, kw)
        log("Failed: $why")
        return FAIL
    }

    private suspend fun handle(p: JSONObject, kw: Boolean, cfg: Cfg): Int {
        val id = p.getString("id")
        val text = p.optString("text").trim().take(900)
        store.add(id) // mark first, so this post is never tried twice
        log("@${p.optString("handle")}${if (kw) " [keyword]" else ""}: ${text.replace("\n", " ").take(70)}")

        val gen = agent.reply(cfg.rules, text, cfg.maxWords)
        val comment = gen.text
        if (comment == null) {
            addHist("SKIPPED", p, "", gen.reason, kw)
            log("Skipped: ${gen.reason}")
            return SKIP
        }
        log("Reply: $comment")
        if (!running) { addHist("SKIPPED", p, comment, "stopped before sending", kw); return SKIP }

        if (cfg.like && !p.optBoolean("liked")) {
            val lr = rect(id, "like")
            if (lr != null) { tap(lr); delay(rnd(700, 1500)) }
        }

        val rr = rect(id, "reply") ?: return fail(p, comment, kw, "reply button not found")
        tap(rr)

        val comp = waitFor(8000) {
            (js("ZB.composer()") as? JSONObject)?.takeIf { it.optBoolean("open") && it.optJSONObject("box") != null }
        } ?: return fail(p, comment, kw, "reply box did not open")

        delay(rnd(800, 1500))
        tap(comp.getJSONObject("box"))
        delay(rnd(500, 900))

        val n = (js("ZB.type(${JSONObject.quote(comment)})") as? Number)?.toInt() ?: -1
        if (n <= 0) return fail(p, comment, kw, "could not type the reply")
        delay(rnd(1200, 2500))

        val send = waitFor(5000) {
            val c = js("ZB.composer()") as? JSONObject
            if (c != null && c.optBoolean("sendEnabled")) c.optJSONObject("send") else null
        } ?: return fail(p, comment, kw, "send button not ready")
        tap(send)

        waitFor(10000, 500) {
            val c = js("ZB.composer()") as? JSONObject
            if (c != null && !c.optBoolean("open")) true else null
        } ?: return fail(p, comment, kw, "reply box did not close after send")

        addHist("REPLIED", p, comment, "", kw)
        return OK
    }

    private suspend fun rect(id: String, what: String): JSONObject? {
        val r = js("ZB.rect(${JSONObject.quote(id)}, '$what')") as? JSONObject
        delay(300) // let the scroll settle before tapping
        return r
    }

    private suspend fun scrollDown() {
        js("ZB.scroll(${rnd(500, 800)})")
        delay(rnd(1800, 3200))
    }

    private suspend fun open(url: String) {
        web.loadUrl(url)
        delay(3500)
        waitFor(20000, 700) {
            val a = js("ZB.posts()") as? JSONArray
            if (a != null && a.length() > 0) a else null
        }
    }

    private suspend fun <T : Any> waitFor(timeoutMs: Long, stepMs: Long = 400, f: suspend () -> T?): T? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (running && SystemClock.uptimeMillis() < end) {
            val v = f()
            if (v != null) return v
            delay(stepMs)
        }
        return null
    }

    /** Runs a ZB function in the page and returns its JSON result. */
    private suspend fun js(expr: String): Any? = suspendCancellableCoroutine { c ->
        val code = "(function(){ try { if(!window.ZB){ $script } return JSON.stringify($expr); } " +
            "catch(e){ return JSON.stringify({__err:String(e)}); } })()"
        web.evaluateJavascript(code) { raw ->
            val s = try { JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null }
            val out: Any? = if (s == null) null else try { JSONTokener(s).nextValue() } catch (e: Exception) { s }
            if (c.isActive) c.resume(if (out == JSONObject.NULL) null else out)
        }
    }

    /** A real touch at the element's centre (slightly randomised), so clicks are trusted. */
    private suspend fun tap(r: JSONObject) {
        val k = web.width / r.getDouble("iw")
        val x = ((r.getDouble("x") + (Math.random() - 0.5) * r.getDouble("w") * 0.5) * k).toFloat()
        val y = ((r.getDouble("y") + (Math.random() - 0.5) * r.getDouble("h") * 0.5) * k).toFloat()
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        web.dispatchTouchEvent(down)
        down.recycle()
        delay(rnd(55, 120))
        val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        web.dispatchTouchEvent(up)
        up.recycle()
    }
}
