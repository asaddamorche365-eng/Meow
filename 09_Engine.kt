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
    val mode: String,      // "feed" or "search"
    val query: String,
    val like: Boolean,
    val rules: String,
    val maxWords: Int,
    val own: String
)

/**
 * The loop. Script part (zb.js) reads the page and the app taps it with real touches.
 * The agent (offline model) only writes the reply text.
 * Everything runs on the main thread; the model call runs on a background thread.
 */
class Engine(
    private val web: WebView,
    private val agent: Agent,
    private val store: Store,
    private val log: (String) -> Unit
) {
    @Volatile var running = false
    private var job: Job? = null
    private val script: String by lazy {
        web.context.assets.open("zb.js").bufferedReader().use { it.readText() }
    }

    // ---- pacing (milliseconds), change here ----
    private val replyPause = 8000 to 20000      // wait after a reply
    private val skipPause = 1500 to 3500        // wait after a skipped post
    private val breakEvery = 25                 // long break after this many replies
    private val breakPause = 120000 to 240000   // length of the long break

    fun start(scope: CoroutineScope, cfg: Cfg, onStop: () -> Unit) {
        if (running) return
        running = true
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

    private fun rnd(r: Pair<Int, Int>): Long = rnd(r.first, r.second)
    private fun rnd(a: Int, b: Int): Long = a + (Math.random() * (b - a)).toLong()

    private suspend fun loop(cfg: Cfg) {
        val startUrl = if (cfg.mode == "search")
            "https://x.com/search?q=" + URLEncoder.encode(cfg.query, "UTF-8") + "&src=typed_query&f=live"
        else "https://x.com/home"

        var own = cfg.own.trim().removePrefix("@")
        var errors = 0
        var replies = 0
        var empty = 0

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
            val p = pick(posts, own)
            if (p == null) {
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

            when (handle(p, cfg)) {
                OK -> {
                    replies++
                    log("Replied. Total this run: $replies")
                    if (replies % breakEvery == 0) {
                        log("Taking a long break.")
                        delay(rnd(breakPause))
                    } else {
                        delay(rnd(replyPause))
                    }
                    scrollDown()
                }
                SKIP -> {
                    delay(rnd(skipPause))
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

    /** First post that is new, not an ad, not our own, and has some text. */
    private fun pick(posts: JSONArray, own: String): JSONObject? {
        for (i in 0 until posts.length()) {
            val p = posts.optJSONObject(i) ?: continue
            val id = p.optString("id")
            if (id.isEmpty() || store.has(id)) continue
            if (p.optBoolean("ad") ||
                p.optString("handle").equals(own, true) ||
                p.optString("text").trim().length < 8
            ) { store.add(id); continue }
            return p
        }
        return null
    }

    private suspend fun handle(p: JSONObject, cfg: Cfg): Int {
        val id = p.getString("id")
        val text = p.optString("text").trim().take(900)
        store.add(id) // mark first, so this post is never tried twice
        log("@${p.optString("handle")}: ${text.take(70)}")

        val comment = agent.reply(cfg.rules, text, cfg.maxWords)
        if (comment == null) { log("Skipped."); return SKIP }
        log("Reply: $comment")
        if (!running) return SKIP

        if (cfg.like && !p.optBoolean("liked")) {
            val lr = rect(id, "like")
            if (lr != null) { tap(lr); delay(rnd(700, 1500)) }
        }

        val rr = rect(id, "reply") ?: return FAIL
        tap(rr)

        val comp = waitFor(8000) {
            (js("ZB.composer()") as? JSONObject)?.takeIf { it.optBoolean("open") && it.optJSONObject("box") != null }
        } ?: return FAIL

        delay(rnd(800, 1500))
        tap(comp.getJSONObject("box"))
        delay(rnd(500, 900))

        val n = (js("ZB.type(${JSONObject.quote(comment)})") as? Number)?.toInt() ?: -1
        if (n <= 0) return FAIL
        delay(rnd(1200, 2500))

        val send = waitFor(5000) {
            val c = js("ZB.composer()") as? JSONObject
            if (c != null && c.optBoolean("sendEnabled")) c.optJSONObject("send") else null
        } ?: return FAIL
        tap(send)

        waitFor(10000, 500) {
            val c = js("ZB.composer()") as? JSONObject
            if (c != null && !c.optBoolean("open")) true else null
        } ?: return FAIL

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
