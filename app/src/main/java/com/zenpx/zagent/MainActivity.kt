package com.zenpx.zagent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import kotlinx.coroutines.*
import java.io.File

class MainActivity : Activity() {

    companion object {
        const val REQ_MODEL = 11
        const val REQ_DONE = 12
        const val SEARCH_URL = "https://www.google.com/search?q="
        const val DEFAULT_RULES =
            "Casual, friendly, human tone. Add one relevant thought or question about the post. " +
            "Max one emoji. Never mention being an AI. Do not repeat the post."
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val logLines = ArrayDeque<String>()

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var store: Store
    private lateinit var agent: Agent
    private lateinit var engine: Engine

    private lateinit var web: WebView
    private lateinit var addr: EditText
    private lateinit var panel: ScrollView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var rbFeed: RadioButton
    private lateinit var rbSearch: RadioButton
    private lateinit var etQuery: EditText
    private lateinit var etOwn: EditText
    private lateinit var etWords: EditText
    private lateinit var etRules: EditText
    private lateinit var cbLike: CheckBox
    private lateinit var cbDesktop: CheckBox
    private lateinit var cbGpu: CheckBox

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("zagent", Context.MODE_PRIVATE)
        store = Store(this)
        agent = Agent(this)
        buildUi()
        engine = Engine(web, agent, store) { log(it) }
        applyViewMode(cbDesktop.isChecked, false)
        web.loadUrl(prefs.getString("last", "https://x.com/home") ?: "https://x.com/home")
        log("Ready. Done posts remembered: ${store.size()}. Model file: ${if (agent.modelFile.exists()) "found" else "not imported yet"}.")
    }

    // ------------------------------------------------------------------ UI

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }

    private fun edit(hint: String, multi: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        textSize = 13f
        if (multi) { minLines = 2; maxLines = 4 } else setSingleLine()
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1419"))
        }

        // top bar
        addr = EditText(this).apply {
            setSingleLine()
            hint = "Search or type URL"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(addr, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("Go") { go() })
            addView(btn("Agent") {
                panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            })
        }
        root.addView(bar)

        // web view
        web = WebView(this)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!addr.hasFocus()) addr.setText(url)
                prefs.edit().putString("last", url).apply()
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // agent panel
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        panel = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#16202A"))
            addView(inner)
        }

        rbFeed = RadioButton(this).apply { text = "Feed"; setTextColor(Color.WHITE) }
        rbSearch = RadioButton(this).apply { text = "Search"; setTextColor(Color.WHITE) }
        val rg = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbFeed); addView(rbSearch)
        }
        if (prefs.getString("mode", "feed") == "search") rbSearch.isChecked = true else rbFeed.isChecked = true
        inner.addView(rg)

        etQuery = edit("Search query (Search mode)").apply { setText(prefs.getString("query", "")) }
        inner.addView(etQuery)

        etOwn = edit("Your X username, no @").apply { setText(prefs.getString("own", "")) }
        etWords = edit("Max words").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("words", "20"))
        }
        inner.addView(row(
            etOwn.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f) },
            etWords.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        ).also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })

        etRules = edit("Rules for replies", true).apply { setText(prefs.getString("rules", DEFAULT_RULES)) }
        inner.addView(etRules)

        cbLike = CheckBox(this).apply { text = "Like"; setTextColor(Color.WHITE); isChecked = prefs.getBoolean("like", true) }
        cbDesktop = CheckBox(this).apply {
            text = "Desktop"; setTextColor(Color.WHITE); isChecked = prefs.getBoolean("desktop", false)
            setOnCheckedChangeListener { _, c -> applyViewMode(c, true) }
        }
        cbGpu = CheckBox(this).apply { text = "GPU"; setTextColor(Color.WHITE); isChecked = prefs.getBoolean("gpu", false) }
        inner.addView(row(cbLike, cbDesktop, cbGpu))

        inner.addView(row(
            btn("Import model") { pick(REQ_MODEL) },
            btn("Load model") { loadModel() },
            btn("Import done links") { pick(REQ_DONE) }
        ))
        inner.addView(row(
            btn("START") { startAgent() },
            btn("STOP") { engine.stop() }
        ))

        tvLog = TextView(this).apply { setTextColor(Color.parseColor("#9FB3C8")); textSize = 11f }
        logScroll = ScrollView(this).apply { addView(tvLog) }
        inner.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))

        root.addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.42).toInt()))

        setContentView(root)
    }

    private fun go() {
        val t = addr.text.toString().trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> SEARCH_URL + Uri.encode(t)
            else -> "https://$t"
        }
        web.loadUrl(url)
        addr.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(addr.windowToken, 0)
    }

    private fun applyViewMode(desktop: Boolean, reload: Boolean) {
        val s = web.settings
        s.userAgentString = if (desktop) DESKTOP_UA else MOBILE_UA
        s.useWideViewPort = true
        s.loadWithOverviewMode = desktop
        if (reload) web.reload()
    }

    private fun log(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logLines.addLast("$t  $msg")
        while (logLines.size > 60) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------ actions

    private fun saveCfg() {
        prefs.edit()
            .putString("mode", if (rbSearch.isChecked) "search" else "feed")
            .putString("query", etQuery.text.toString())
            .putString("own", etOwn.text.toString())
            .putString("words", etWords.text.toString())
            .putString("rules", etRules.text.toString())
            .putBoolean("like", cbLike.isChecked)
            .putBoolean("desktop", cbDesktop.isChecked)
            .putBoolean("gpu", cbGpu.isChecked)
            .apply()
    }

    private fun pick(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == REQ_MODEL) importModel(uri)
        if (requestCode == REQ_DONE) importDone(uri)
    }

    private fun importModel(uri: Uri) {
        if (engine.running) { log("Stop the agent first."); return }
        scope.launch {
            log("Copying model, keep the app open...")
            val msg = withContext(Dispatchers.IO) {
                try {
                    agent.unload()
                    val dest = agent.modelFile
                    dest.parentFile?.mkdirs()
                    val tmp = File(dest.parentFile, "model.tmp")
                    contentResolver.openInputStream(uri)!!.use { i ->
                        tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
                    }
                    if (dest.exists()) dest.delete()
                    tmp.renameTo(dest)
                    "Model imported (${dest.length() / 1048576} MB). Tap Load model."
                } catch (e: Throwable) {
                    "Import failed: ${e.message}"
                }
            }
            log(msg)
        }
    }

    private fun importDone(uri: Uri) {
        if (engine.running) { log("Stop the agent first."); return }
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    store.importText(text)
                } catch (e: Throwable) { -1 }
            }
            log(if (n < 0) "Could not read that file." else "Imported $n done posts. Total remembered: ${store.size()}.")
        }
    }

    private fun loadModel() {
        if (engine.running) return
        saveCfg()
        scope.launch {
            log("Loading model (10-30 s)...")
            log(agent.load(cbGpu.isChecked))
        }
    }

    private fun startAgent() {
        if (engine.running) return
        saveCfg()
        val mode = if (rbSearch.isChecked) "search" else "feed"
        val q = etQuery.text.toString().trim()
        if (mode == "search" && q.isEmpty()) { log("Type a search query first."); return }
        val words = etWords.text.toString().toIntOrNull()?.coerceIn(5, 60) ?: 20
        scope.launch {
            if (!agent.ready) {
                log("Loading model (10-30 s)...")
                log(agent.load(cbGpu.isChecked))
            }
            if (!agent.ready) return@launch
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val cfg = Cfg(
                mode = mode, query = q, like = cbLike.isChecked,
                rules = etRules.text.toString().ifBlank { DEFAULT_RULES },
                maxWords = words, own = etOwn.text.toString()
            )
            log("Agent started: $mode. Do not pinch-zoom while it runs.")
            engine.start(scope, cfg) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                log("Agent stopped.")
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        saveCfg()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        engine.stop()
        scope.cancel()
        agent.unload()
        web.destroy()
        super.onDestroy()
    }
}
