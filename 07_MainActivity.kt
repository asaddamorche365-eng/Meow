package com.zenpx.zagent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
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
        val BLUE = Color.parseColor("#1D9BF0")
        val GREEN = Color.parseColor("#1DB954")
        val RED = Color.parseColor("#E0245E")
        val GRAY = Color.parseColor("#8FA3B8")
        val BTN = Color.parseColor("#2A3947")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val logLines = ArrayDeque<String>()

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var store: Store
    private lateinit var history: History
    private lateinit var agent: Agent
    private lateinit var engine: Engine

    private lateinit var rootView: FrameLayout
    private lateinit var web: WebView
    private lateinit var addr: EditText

    // floating dock
    private lateinit var dock: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var stopBtn: TextView
    private lateinit var countTv: TextView

    // panel
    private lateinit var panel: LinearLayout
    private val tabViews = ArrayList<View>()
    private val tabBtns = ArrayList<Button>()
    private var currentTab = 0

    // control tab
    private lateinit var rbFeed: RadioButton
    private lateinit var rbSearch: RadioButton
    private lateinit var etQuery: EditText
    private lateinit var etOwn: EditText
    private lateinit var cbLike: CheckBox
    private lateinit var cbDesktop: CheckBox
    private lateinit var cbGpu: CheckBox
    private lateinit var tvStatus: TextView

    // agent tab
    private lateinit var etRules: EditText
    private lateinit var etWords: EditText
    private lateinit var etKeywords: EditText
    private lateinit var cbOnlyKw: CheckBox
    private lateinit var etSkipWords: EditText
    private lateinit var etMinLen: EditText
    private lateinit var etP1: EditText
    private lateinit var etP2: EditText
    private lateinit var etK1: EditText
    private lateinit var etK2: EditText
    private lateinit var etMax: EditText

    // history tab
    private var histFilter = ""
    private lateinit var tvHistSummary: TextView
    private lateinit var histList: LinearLayout

    // log tab
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("zagent", Context.MODE_PRIVATE)
        store = Store(this)
        history = History(this)
        agent = Agent(this)
        buildUi()
        engine = Engine(web, agent, store, history, { log(it) }, { onEngineChange() })
        applyViewMode(cbDesktop.isChecked, false)
        web.loadUrl(prefs.getString("last", "https://x.com/home") ?: "https://x.com/home")
        log("Ready. Done posts remembered: ${store.size()}. Model file: ${if (agent.modelFile.exists()) "found" else "not imported yet"}.")
        refreshStatus()
    }

    // ------------------------------------------------------------------ small UI helpers

    private fun shape(color: Int, radius: Float = 0f, oval: Boolean = false) = GradientDrawable().apply {
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = radius
        setColor(color)
    }

    private fun btn(text: String, color: Int = BTN, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        minHeight = 0
        minimumHeight = 0
        background = shape(color, dp(10).toFloat())
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    private fun edit(hint: String, multi: Boolean = false, number: Boolean = false, value: String = "") = EditText(this).apply {
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        textSize = 13f
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
        if (multi) { minLines = 2; maxLines = 5 } else if (!number) setSingleLine()
        setText(value)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(GRAY); textSize = 11f; setPadding(0, dp(10), 0, 0)
    }

    private fun check(t: String, on: Boolean) = CheckBox(this).apply {
        text = t; setTextColor(Color.WHITE); isChecked = on
    }

    private fun col() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(12))
    }

    /** Horizontal row where every child gets equal width. */
    private fun rowW(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        v.forEach {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
    }

    private fun scrollOf(inner: View) = ScrollView(this).apply { addView(inner); visibility = View.GONE }

    private fun circle(text: String, size: Int, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = shape(color, oval = true)
        elevation = dp(6).toFloat()
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(3), 0, dp(3), 0) }
    }

    // ------------------------------------------------------------------ UI

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun buildUi() {
        rootView = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0F1419")) }

        // browser: slim address bar + full page
        addr = EditText(this).apply {
            setSingleLine()
            hint = "Search or type URL"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 13f
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(addr, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("Go") { go() })
        }
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
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        rootView.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        buildPanel()
        buildDock()
        showTab(0)
    }

    // ---- floating dock: drag the Z, tap it to open the panel ----
    @SuppressLint("ClickableViewAccessibility")
    private fun buildDock() {
        bubble = circle("Z", 46, BLUE)
        stopBtn = circle("\u25A0", 38, RED).apply {
            visibility = View.GONE
            setOnClickListener { engine.stop() }
        }
        countTv = TextView(this).apply {
            visibility = View.GONE
            textSize = 12f
            setTextColor(Color.WHITE)
            background = shape(Color.parseColor("#CC000000"), dp(12).toFloat())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bubble)
            addView(stopBtn)
            addView(countTv)
        }
        rootView.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rootView.post {
            dock.x = prefs.getFloat("dx", rootView.width - dp(60).toFloat())
            dock.y = prefs.getFloat("dy", rootView.height * 0.35f)
            clampDock()
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var offX = 0f; var offY = 0f; var sx = 0f; var sy = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY
                        offX = dock.x - e.rawX; offY = dock.y - e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(e.rawX - sx) > dp(8) || Math.abs(e.rawY - sy) > dp(8)) moved = true
                        if (moved) {
                            dock.x = e.rawX + offX
                            dock.y = e.rawY + offY
                            clampDock()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel()
                        else prefs.edit().putFloat("dx", dock.x).putFloat("dy", dock.y).apply()
                    }
                }
                return true
            }
        })
    }

    private fun clampDock() {
        dock.x = dock.x.coerceIn(0f, maxOf(0f, (rootView.width - dock.width).toFloat()))
        dock.y = dock.y.coerceIn(0f, maxOf(0f, (rootView.height - dock.height).toFloat()))
    }

    // ---- panel with 4 tabs ----
    private fun buildPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2121A24"))
            visibility = View.GONE
            elevation = dp(10).toFloat()
        }

        val names = listOf("Control", "Agent", "History", "Log")
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), 0) }
        names.forEachIndexed { i, n ->
            val b = btn(n) { showTab(i) }
            tabBtns.add(b)
            tabBar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        tabBar.addView(btn("\u2715", RED) { togglePanel() })
        panel.addView(tabBar)

        val content = FrameLayout(this)
        content.addView(controlTab())
        content.addView(agentTab())
        content.addView(historyTab())
        content.addView(logTab())
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        rootView.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.58).toInt(), Gravity.BOTTOM))
        setContentView(rootView)
    }

    private fun controlTab(): View {
        val c = col()
        rbFeed = RadioButton(this).apply { text = "Feed"; setTextColor(Color.WHITE) }
        rbSearch = RadioButton(this).apply { text = "Search"; setTextColor(Color.WHITE) }
        val rg = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbFeed); addView(rbSearch)
        }
        if (prefs.getString("mode", "feed") == "search") rbSearch.isChecked = true else rbFeed.isChecked = true
        c.addView(rg)
        etQuery = edit("Search query (for Search mode)", value = prefs.getString("query", "") ?: "")
        etOwn = edit("Your X username, no @", value = prefs.getString("own", "") ?: "")
        c.addView(etQuery)
        c.addView(etOwn)
        cbLike = check("Like", prefs.getBoolean("like", true))
        cbDesktop = check("Desktop", prefs.getBoolean("desktop", false)).apply {
            setOnCheckedChangeListener { _, on -> applyViewMode(on, true) }
        }
        cbGpu = check("GPU", prefs.getBoolean("gpu", false))
        c.addView(rowW(cbLike, cbDesktop, cbGpu))
        c.addView(rowW(btn("Import model") { pick(REQ_MODEL) }, btn("Load model") { loadModel() }))
        c.addView(btn("Import done links") { pick(REQ_DONE) })
        c.addView(rowW(btn("START", GREEN) { startAgent() }, btn("STOP", RED) { engine.stop() }))
        tvStatus = TextView(this).apply { setTextColor(GRAY); textSize = 12f; setPadding(0, dp(10), 0, 0) }
        c.addView(tvStatus)
        val sv = scrollOf(c); tabViews.add(sv); return sv
    }

    private fun agentTab(): View {
        val c = col()
        c.addView(label("Reply rules (tone, style, what to avoid)"))
        etRules = edit("Rules", multi = true, value = prefs.getString("rules", DEFAULT_RULES) ?: DEFAULT_RULES)
        c.addView(etRules)
        c.addView(label("Max words per reply"))
        etWords = edit("20", number = true, value = prefs.getString("words", "20") ?: "20")
        c.addView(etWords)
        c.addView(label("Keywords (comma separated). Matching posts get the keyword speed."))
        etKeywords = edit("e.g. airdrop, mainnet, testnet", value = prefs.getString("kw", "") ?: "")
        c.addView(etKeywords)
        cbOnlyKw = check("Reply only to posts that contain a keyword", prefs.getBoolean("onlykw", false))
        c.addView(cbOnlyKw)
        c.addView(label("Skip words (comma separated). Posts with these are skipped."))
        etSkipWords = edit("e.g. giveaway, dm me", value = prefs.getString("skipw", "") ?: "")
        c.addView(etSkipWords)
        c.addView(label("Minimum post length (characters)"))
        etMinLen = edit("8", number = true, value = prefs.getString("minlen", "8") ?: "8")
        c.addView(etMinLen)
        c.addView(label("Pause after a normal reply, seconds (min / max)"))
        etP1 = edit("min", number = true, value = prefs.getString("p1", "8") ?: "8")
        etP2 = edit("max", number = true, value = prefs.getString("p2", "20") ?: "20")
        c.addView(rowW(etP1, etP2))
        c.addView(label("Pause after a keyword post reply, seconds (min / max)"))
        etK1 = edit("min", number = true, value = prefs.getString("k1", "3") ?: "3")
        etK2 = edit("max", number = true, value = prefs.getString("k2", "6") ?: "6")
        c.addView(rowW(etK1, etK2))
        c.addView(label("Stop after this many replies (0 = no limit)"))
        etMax = edit("0", number = true, value = prefs.getString("max", "0") ?: "0")
        c.addView(etMax)
        val sv = scrollOf(c); tabViews.add(sv); return sv
    }

    private fun historyTab(): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val filters = rowW(
            btn("All") { histFilter = ""; refreshHistory() },
            btn("Replied", GREEN) { histFilter = "REPLIED"; refreshHistory() },
            btn("Skipped", Color.parseColor("#B8860B")) { histFilter = "SKIPPED"; refreshHistory() },
            btn("Failed", RED) { histFilter = "FAILED"; refreshHistory() },
            btn("Clear") { history.clear(); engine.filteredBy.clear(); refreshHistory() }
        )
        outer.addView(filters)
        tvHistSummary = TextView(this).apply { setTextColor(GRAY); textSize = 11f; setPadding(dp(12), dp(6), dp(12), dp(4)) }
        outer.addView(tvHistSummary)
        histList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(12)) }
        val sv = ScrollView(this).apply { addView(histList) }
        outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        tabViews.add(outer)
        return outer
    }

    private fun logTab(): View {
        tvLog = TextView(this).apply { setTextColor(GRAY); textSize = 11f; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        logScroll = scrollOf(tvLog)
        tabViews.add(logScroll)
        return logScroll
    }

    private fun showTab(i: Int) {
        currentTab = i
        tabViews.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabBtns.forEachIndexed { k, b -> b.background = shape(if (k == i) BLUE else BTN, dp(10).toFloat()) }
        if (i == 2) refreshHistory()
        if (i == 3) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun togglePanel() {
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(rootView.windowToken, 0)
        if (panel.visibility == View.VISIBLE) { refreshStatus(); if (currentTab == 2) refreshHistory() }
    }

    // ------------------------------------------------------------------ history view

    private fun refreshHistory() {
        histList.removeAllViews()
        val items = history.items.filter { histFilter.isEmpty() || it.status == histFilter }.asReversed().take(150)
        val filt = if (engine.filteredBy.isEmpty()) ""
        else "\nPassed over this run (not listed): " + engine.filteredBy.entries.joinToString(", ") { "${it.key} ${it.value}" }
        tvHistSummary.text = "Replied ${history.count("REPLIED")}   Skipped ${history.count("SKIPPED")}   " +
            "Failed ${history.count("FAILED")}$filt\nTap an entry to open the post."
        if (items.isEmpty()) {
            histList.addView(TextView(this).apply { text = "Nothing here yet."; setTextColor(GRAY); setPadding(0, dp(12), 0, 0) })
            return
        }
        val fmt = java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.US)
        for (e in items) {
            val color = when (e.status) {
                "REPLIED" -> Color.parseColor("#3DDC84")
                "SKIPPED" -> Color.parseColor("#FFB74D")
                else -> Color.parseColor("#FF6B6B")
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(Color.parseColor("#1B2733"), dp(8).toFloat())
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
                setOnClickListener {
                    if (e.handle.isNotEmpty() && e.id.isNotEmpty()) {
                        web.loadUrl("https://x.com/${e.handle}/status/${e.id}")
                        togglePanel()
                    }
                }
            }
            box.addView(TextView(this).apply {
                text = "${fmt.format(java.util.Date(e.time))}  ${e.status}${if (e.kw) "  [keyword]" else ""}  @${e.handle}"
                setTextColor(color); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            })
            box.addView(TextView(this).apply { text = e.text; setTextColor(GRAY); textSize = 12f })
            if (e.reply.isNotEmpty()) box.addView(TextView(this).apply {
                text = "Reply: ${e.reply}"; setTextColor(Color.WHITE); textSize = 13f
            })
            if (e.reason.isNotEmpty()) box.addView(TextView(this).apply {
                text = "Why: ${e.reason}"; setTextColor(color); textSize = 12f
            })
            histList.addView(box)
        }
    }

    private fun onEngineChange() {
        countTv.text = "${engine.replies} replied"
        if (panel.visibility == View.VISIBLE && currentTab == 2) refreshHistory()
    }

    private fun refreshStatus() {
        tvStatus.text = "Model: ${if (agent.ready) "loaded" else if (agent.modelFile.exists()) "imported, not loaded" else "not imported"}\n" +
            "Done posts remembered: ${store.size()}"
    }

    private fun setRunningUi(on: Boolean) {
        bubble.background = shape(if (on) GREEN else BLUE, oval = true)
        stopBtn.visibility = if (on) View.VISIBLE else View.GONE
        countTv.visibility = if (on) View.VISIBLE else View.GONE
        countTv.text = "${engine.replies} replied"
        dock.post { clampDock() }
    }

    // ------------------------------------------------------------------ browser

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
        logLines.addLast("$t  ${msg.replace("\n", " ")}")
        while (logLines.size > 80) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
        if (currentTab == 3) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------ actions

    private fun saveCfg() {
        prefs.edit()
            .putString("mode", if (rbSearch.isChecked) "search" else "feed")
            .putString("query", etQuery.text.toString())
            .putString("own", etOwn.text.toString())
            .putBoolean("like", cbLike.isChecked)
            .putBoolean("desktop", cbDesktop.isChecked)
            .putBoolean("gpu", cbGpu.isChecked)
            .putString("rules", etRules.text.toString())
            .putString("words", etWords.text.toString())
            .putString("kw", etKeywords.text.toString())
            .putBoolean("onlykw", cbOnlyKw.isChecked)
            .putString("skipw", etSkipWords.text.toString())
            .putString("minlen", etMinLen.text.toString())
            .putString("p1", etP1.text.toString())
            .putString("p2", etP2.text.toString())
            .putString("k1", etK1.text.toString())
            .putString("k2", etK2.text.toString())
            .putString("max", etMax.text.toString())
            .apply()
    }

    private fun num(e: EditText, def: Int) = e.text.toString().trim().toIntOrNull() ?: def
    private fun words(e: EditText) = e.text.toString().split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

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
            refreshStatus()
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
            refreshStatus()
        }
    }

    private fun loadModel() {
        if (engine.running) return
        saveCfg()
        scope.launch {
            log("Loading model (10-30 s)...")
            log(agent.load(cbGpu.isChecked))
            refreshStatus()
        }
    }

    private fun startAgent() {
        if (engine.running) return
        saveCfg()
        val mode = if (rbSearch.isChecked) "search" else "feed"
        val q = etQuery.text.toString().trim()
        if (mode == "search" && q.isEmpty()) { log("Type a search query first."); return }
        val cfg = Cfg(
            mode = mode,
            query = q,
            like = cbLike.isChecked,
            rules = etRules.text.toString().ifBlank { DEFAULT_RULES },
            maxWords = num(etWords, 20).coerceIn(5, 60),
            own = etOwn.text.toString(),
            keywords = words(etKeywords),
            onlyKeyword = cbOnlyKw.isChecked,
            skipWords = words(etSkipWords),
            minLen = num(etMinLen, 8),
            pause = num(etP1, 8) to num(etP2, 20),
            kwPause = num(etK1, 3) to num(etK2, 6),
            maxReplies = num(etMax, 0)
        )
        if (cfg.onlyKeyword && cfg.keywords.isEmpty()) { log("Add at least one keyword, or turn off keyword-only."); return }
        scope.launch {
            if (!agent.ready) {
                log("Loading model (10-30 s)...")
                log(agent.load(cbGpu.isChecked))
                refreshStatus()
            }
            if (!agent.ready) return@launch
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            log("Agent started: $mode. Do not pinch-zoom while it runs.")
            panel.visibility = View.GONE
            setRunningUi(true)
            engine.start(scope, cfg) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setRunningUi(false)
                log("Agent stopped.")
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            panel.visibility == View.VISIBLE -> togglePanel()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
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
