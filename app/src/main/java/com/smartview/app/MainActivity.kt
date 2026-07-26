package com.smartview.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * SmartView — a sleek voice-first browser for the RayNeo X3 Pro.
 *
 *  - No scroll bars: vertical edge scrolling (cursor parked at top/bottom edge).
 *  - Double-tap the right temple: speak a command —
 *      "search <q> on duckduckgo|google"   → web search
 *      "bookmark this web page"            → save with auto keywords
 *      "delete <keyword> bookmark"         → remove bookmark
 *      "open bookmarks"                    → bookmark manager (+ Groq API key)
 *      "refresh web page"                  → reload
 *      "open <keyword>"                    → open matching bookmark
 *      anything else                       → page-agent task on the current page
 *  - page-agent (alibaba/page-agent, bundled) automates the current page via the
 *    user's Groq API key; its output is spoken with TTS and the user replies by
 *    voice. It auto-hides after 5 s without interaction.
 *  - The on-screen keyboard has a Mic key: Groq Whisper dictation into any field
 *    (per TAPLINKX3 v1.7.0).
 */
class MainActivity : android.app.Activity(), CustomKeyboardView.OnKeyboardActionListener {
    private lateinit var webView: WebView
    private lateinit var viewport: FrameLayout
    private lateinit var keyboardContainer: FrameLayout
    private lateinit var binocular: BinocularSbsLayout
    private lateinit var statusPill: TextView
    private lateinit var dimOverlay: View
    private var dimMode = false
    private var keyboardView: CustomKeyboardView? = null
    private val main = Handler(Looper.getMainLooper())
    private val imeSuppressor = Handler(Looper.getMainLooper())
    private var suppressImeUntilMs = 0L

    private lateinit var bookmarks: BookmarkStore
    private val recorder by lazy { GroqSpeech.Recorder(this) }
    private var pageAgentJs: String? = null

    private enum class VoiceMode { NONE, COMMAND, AGENT_ANSWER, DICTATION }
    private var voiceMode = VoiceMode.NONE
    private var agentVisible = false

    /** True from task dispatch until a terminal callback. Distinct from
     *  agentVisible (which tracks the panel) because the panel can be hidden
     *  while a task is still stepping. */
    private var agentRunning = false
    /** Outstanding page-agent ask_user request id awaiting a spoken answer. */
    private var pendingAskId: String? = null
    private val autoStopRecording = Runnable { if (recorder.isRecording) stopVoiceAndProcess() }
    private val agentHideRunnable = Runnable { hideAgentPanel() }
    private val statusHideRunnable = Runnable { statusPill.visibility = View.GONE }
    private val keyboardHideRunnable = Runnable { hideKeyboard() }

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartView:Voice").apply {
                setReferenceCounted(false)
            }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching { if (!wakeLock.isHeld) wakeLock.acquire() }
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        enableImmersiveFullscreen()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 4001)
        }

        bookmarks = BookmarkStore(this)
        GroqSpeech.onSpeechError = { msg -> showStatus(msg, 6000) }
        pageAgentJs = runCatching {
            assets.open("page-agent.js").readBytes().toString(Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "page-agent asset missing: ${it.message}") }.getOrNull()

        CookieManager.getInstance().setAcceptCookie(true)
        WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Black canvas (waveguide black = transparent/off), like the TapLink browser.
            setBackgroundColor(Color.BLACK)
            // Sleek: no scroll bars anywhere — vertical edge scrolling replaces them.
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            configure(this)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        keyboardContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
            visibility = View.GONE
            elevation = 3000f
        }

        statusPill = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = 10 }
            setBackgroundColor(Color.argb(215, 16, 20, 28))
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(18, 8, 18, 8)
            elevation = 4000f
            visibility = View.GONE
        }

        viewport = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(webView)
            addView(keyboardContainer)
            addView(statusPill)
        }

        binocular = BinocularSbsLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            logicalClickHandler = { x, y -> handleLogicalClick(x, y) }
            edgeScrollHandler = { dy -> scrollPage(dy) }
            // Ordered so the gesture always undoes the most recent thing. Before
            // this, reaching for A/Z/Caps/Clear at the keyboard's left edge went
            // BACK and discarded whatever had been typed.
            leftEdgeBackHandler = {
                when {
                    dimMode -> exitDim()
                    keyboardContainer.visibility == View.VISIBLE -> hideKeyboard()
                    else -> goBack()
                }
            }
            rightEdgePullHandler = { if (!dimMode) enterDim() }
            contentInteractionBlocked = { dimMode || keyboardContainer.visibility == View.VISIBLE }
            doubleTapHandler = { onDoubleTap() }
            tripleTapHandler = { onTripleTap() }
            tapInterceptor = { interceptTap() }
            addView(viewport, 0)
            setWebViewTarget(webView)
        }
        // Dim-mode blackout: a full black layer over both eyes. On the waveguide
        // black = display off, so the view goes dark while the WebView (and its
        // audio) keeps running. Added last so it draws on top of the cursor.
        dimOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        binocular.addView(dimOverlay)

        setContentView(binocular)
        enableImmersiveFullscreen()

        webView.loadUrl(HOME)
    }

    // ------------------------------------------------------------------
    //  WebView config
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setOffscreenPreRaster(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // The device's System WebView is Chrome 95 (2021). Cloudflare Turnstile
            // distrusts such an old engine and loops the "verify human" challenge, so
            // present a current mobile-Chrome identity (drops the ";wv" token too).
            userAgentString = MODERN_UA
        }
        runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true) }
        disableSystemKeyboard(wv)
        wv.addJavascriptInterface(SvBridge(), "SvBridge")

        AdBlock.warmUp(this)
        // Service-worker requests never reach WebViewClient, so a page with a SW
        // (most large sites) would otherwise fetch its ads straight past the
        // filter. Route them through the same check.
        runCatching {
            android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(
                object : android.webkit.ServiceWorkerClient() {
                    override fun shouldInterceptRequest(
                        request: WebResourceRequest
                    ): WebResourceResponse? = AdBlock.intercept(request)
                }
            )
        }

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? = AdBlock.intercept(request)

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectPolyfills()
                AdBlock.resetCount()
                if (url == null || !url.startsWith(INTERNAL_BASE)) injectDarkMode()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectPolyfills()
                // Mirrors the internal-page guard used for dark mode: the
                // settings page is ours and must never be filtered.
                if (url == null || !url.startsWith(INTERNAL_BASE)) injectCosmeticFilter()
                Log.d(TAG, "adblock: ${AdBlock.blockCount()} requests blocked on ${url?.take(60)}")
                injectKeyboardSupport()
                if (url != null && !url.startsWith(INTERNAL_BASE)) { injectDarkMode(); injectPageAgent() }
                CookieManager.getInstance().flush()
            }
        }
    }

    /** Vertical edge scrolling — scrolls the page (or its tallest scroller). */
    private fun scrollPage(dy: Int) {
        if (dy == 0) return
        webView.evaluateJavascript(
            "(function(){var se=document.scrollingElement||document.documentElement;se.scrollTop+=$dy;})();",
            null
        )
    }

    /** Back navigation: left-edge pull (when not dimmed) lands here. */
    private fun goBack() {
        if (!this::webView.isInitialized) return
        when {
            // From Settings (or any page) return to the actual previous page.
            webView.canGoBack() -> { showStatus("‹ Back", 1200); webView.goBack() }
            webView.url?.startsWith(INTERNAL_BASE) == true -> webView.loadUrl(HOME)
            else -> showStatus("No page to go back to", 1500)
        }
    }

    /** Pull-right on the right edge: black out both eyes; audio keeps playing. */
    private fun enterDim() {
        if (dimMode) return
        dimMode = true
        GroqSpeech.stopSpeaking()
        if (recorder.isRecording) { main.removeCallbacks(autoStopRecording); recorder.stop(); voiceMode = VoiceMode.NONE }
        hideKeyboard()
        dimOverlay.visibility = View.VISIBLE
        dimOverlay.bringToFront()
        // KEEP_SCREEN_ON is owned by onCreate for the whole session. Touching it
        // here is what caused the display to sleep mid-article: exitDim used to
        // CLEAR the flag onCreate had set, so one dim/undim cycle silently
        // disabled keep-awake for the rest of the run.
        binocular.invalidate()
    }

    private fun exitDim() {
        if (!dimMode) return
        dimMode = false
        dimOverlay.visibility = View.GONE
        showStatus("Display on", 1200)
        binocular.invalidate()
    }

    // ------------------------------------------------------------------
    //  Voice: double-tap → record → transcribe → route
    // ------------------------------------------------------------------

    /** Any single tap while recording/speaking stops it (consumes the tap). */
    private fun interceptTap(): Boolean {
        if (recorder.isRecording) { stopVoiceAndProcess(); return true }
        if (GroqSpeech.isSpeaking) { GroqSpeech.stopSpeaking(); return true }
        if (agentVisible) scheduleAgentHide() // click = interaction: restart 5s clock
        return false
    }

    private fun onDoubleTap() {
        if (dimMode) return
        if (recorder.isRecording) { stopVoiceAndProcess(); return }
        startVoice(VoiceMode.COMMAND)
    }

    /** Triple-tap → open the bookmarks / Settings page. */
    private fun onTripleTap() {
        // Second way out of dim. The left-edge pull is the only other exit, and
        // it means traversing the full width blind with the cursor hidden under
        // the overlay. Deliberately triple and not double: two stray brushes of
        // the temple pad while adjusting the glasses would flash a lit display
        // into the wearer's eyes.
        if (dimMode) { exitDim(); return }
        GroqSpeech.stopSpeaking()
        if (recorder.isRecording) {
            main.removeCallbacks(autoStopRecording)
            recorder.stop()
            voiceMode = VoiceMode.NONE
        }
        showBookmarksPage()
    }

    private fun startVoice(mode: VoiceMode) {
        if (GroqSpeech.apiKey(this).isEmpty()) {
            showStatus("Set your Groq API key: double-tap and say \"open bookmarks\"", 5000)
            if (mode == VoiceMode.COMMAND) showBookmarksPage()
            return
        }
        GroqSpeech.stopSpeaking()
        if (!recorder.start()) { showStatus("Mic unavailable", 3000); return }
        voiceMode = mode
        cancelAgentHide()
        keyboardView?.setMicActive(mode == VoiceMode.DICTATION)
        showStatus(
            when (mode) {
                VoiceMode.DICTATION -> "🎤 Dictating — tap to stop"
                VoiceMode.AGENT_ANSWER -> "🎤 Answer the agent — tap to stop"
                else -> "🎤 Listening — tap to stop"
            },
            0
        )
        main.removeCallbacks(autoStopRecording)
        main.postDelayed(autoStopRecording, MAX_RECORD_MS)
    }

    private fun stopVoiceAndProcess() {
        main.removeCallbacks(autoStopRecording)
        val mode = voiceMode
        voiceMode = VoiceMode.NONE
        keyboardView?.setMicActive(false)
        val audio = recorder.stop()
        Log.d(TAG, "stopVoice mode=$mode audio=${audio?.length() ?: -1} bytes")
        if (audio == null) { failVoice(mode, "No speech recorded — try again"); return }
        if (!isOnline()) { audio.delete(); failVoice(mode, "📵 No internet connection"); return }
        showStatus("… transcribing", 0)
        GroqSpeech.transcribe(this, audio) { text, error ->
            if (text.isNullOrBlank()) { failVoice(mode, error ?: "Didn't catch that"); return@transcribe }
            when (mode) {
                VoiceMode.DICTATION -> {
                    js("window.__svInsert && window.__svInsert(${JSONObject.quote(text)})")
                    showStatus("⌨ " + text.take(48), 2500)
                }
                VoiceMode.AGENT_ANSWER -> {
                    showStatus("🗣 " + text.take(48), 2500)
                    answerAgent(text)
                }
                else -> routeCommand(text)
            }
        }
    }

    /** A voice attempt failed: show the real reason and unstick any agent loop. */
    private fun failVoice(mode: VoiceMode, msg: String) {
        showStatus(msg, 3500)
        when (mode) {
            VoiceMode.AGENT_ANSWER -> answerAgent("")   // don't hang page-agent's ask_user
            else -> {}
        }
    }

    // ------------------------------------------------------------------
    //  Network / battery status (also drives the Settings status line)
    // ------------------------------------------------------------------

    private fun activeCaps(): android.net.NetworkCapabilities? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return null
        return cm.getNetworkCapabilities(cm.activeNetwork)
    }

    private fun isOnline(): Boolean =
        activeCaps()?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun networkLabel(): String {
        val c = activeCaps() ?: return "Offline"
        return when {
            c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Online"
        }
    }

    private fun batteryPct(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    private fun runSearch(query: String, google: Boolean) {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (google) "https://www.google.com/search?q=$q" else "https://duckduckgo.com/?q=$q"
        showStatus("🔎 " + query.trim(), 2500)
        webView.loadUrl(url)
    }

    /**
     * Spoken help. Without this, "help" was shipped to the LLM as a browsing
     * task — the one word a confused user is most likely to say was also the
     * one guaranteed not to help them.
     */
    private fun showHelp() {
        val lines = listOf(
            "Double-tap to speak · triple-tap for bookmarks",
            "\"search <anything>\" · \"bookmark this\" · \"open <name>\"",
            "\"go back\" · \"scroll down\" · \"refresh\" · \"go home\"",
            "Anything else is handled by the page agent",
            "Pull the left edge to go back · right edge to go dark"
        )
        showStatus(lines.joinToString("  ·  "), 9000)
        GroqSpeech.speak(
            this,
            "Double tap to speak. Say search, bookmark this, or open a bookmark by name. " +
                "Anything else I hand to the page agent."
        ) {}
    }

    /**
     * Normalise a transcript for GRAMMAR MATCHING only.
     *
     * Whisper punctuates freely, and interior punctuation used to defeat the
     * grammar outright: "on duckduckgo" came back as "on duckduck, go", the
     * engine group failed, and the entire sentence — verb and all — became the
     * search query. Observed live.
     *
     * Deliberately a targeted punctuation class and NOT [^a-z0-9' ]: the latter
     * deletes every non-ASCII character and shreds any non-English query.
     */
    private fun normalizeCommand(raw: String): String =
        raw.lowercase()
            .replace(Regex("[,;:!?.\\-_/]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            // Head fillers only, anchored. A global strip corrupts real
            // queries — "search just eat" would become "search eat".
            .replace(Regex("^(?:(?:uh|um|er|ok|okay|hey|please|can you|could you)[\\s]+)+"), "")
            .replace(Regex("\\s+please$"), "")
            .trim()

    /** The voice-command grammar. Anything unmatched becomes a page-agent task. */
    private fun routeCommand(raw: String) {
        val text = normalizeCommand(raw)
        Log.d(TAG, "voice command: $text")

        // Cheap local navigation. These are the most frequent actions and used
        // to fall through to the LLM — a ~60s round trip that then failed
        // anyway, because page-agent's own prompt forbids leaving the page.
        when (text) {
            "go back", "back" -> { goBack(); return }
            "go forward", "forward" -> { showStatus("› Forward", 1200); webView.goForward(); return }
            "go home", "home" -> { showStatus("⌂ Home", 1500); webView.loadUrl(HOME); return }
            "scroll down", "down" -> { scrollPage(420); return }
            "scroll up", "up" -> { scrollPage(-420); return }
            "top", "scroll to top" -> { webView.evaluateJavascript("scrollTo(0,0)", null); return }
            "bottom", "scroll to bottom" -> {
                webView.evaluateJavascript("scrollTo(0,document.body.scrollHeight)", null); return
            }
            "help", "what can i say", "what can you do" -> { showHelp(); return }
        }

        // "search <q> on duckduckgo|google" — engine name tolerant of Whisper's
        // spacing (duck duck go / duckduck go / ddg).
        // The engine alternation tolerates the punctuation Whisper inserts even
        // after normalisation ("duck duck go", "duckduck go", "ddg").
        Regex("^search (?:for )?(.+?)\\s+(?:on|in|using|with|via)\\s+(duck\\s*[,.]?\\s*duck\\s*[,.]?\\s*go|duckduckgo|ddg|google)\\b.*$")
            .find(text)?.let { m ->
                runSearch(m.groupValues[1], m.groupValues[2].startsWith("google"))
                return
            }
        // Bare "search <q>" defaults to DuckDuckGo.
        Regex("^search (?:for )?(.+)$").find(text)?.let { m ->
            runSearch(m.groupValues[1], false)
            return
        }

        // Add-bookmark intents (kept distinct from "delete … bookmark" / "open bookmarks",
        // which are matched below): "bookmark this page", "add bookmark", "save this page".
        // ANCHORED, not contains(). "how do i save this document" used to
        // bookmark the page. The tails stay optional so bare "bookmark" and
        // "add bookmark" — both of which work today — keep working.
        if (Regex("^(?:bookmark|save)(?: this| the)?(?: page)?$").matches(text) ||
            Regex("^add (?:a )?bookmark$").matches(text)
        ) {
            val url = webView.url.orEmpty()
            if (url.isEmpty() || url.startsWith(INTERNAL_BASE)) {
                showStatus("Nothing to bookmark", 2500); return
            }
            val bm = bookmarks.add(url, webView.title.orEmpty())
            val kws = bm.keywords.take(3).joinToString(", ")
            showStatus("★ Bookmarked: $kws", 3500)
            GroqSpeech.speak(this, "Bookmarked. Say open ${bm.keywords.firstOrNull() ?: "it"} to return.") {}
            return
        }

        Regex("^delete (.+?) bookmarks?$").find(text)?.let { m ->
            val victim = bookmarks.deleteByKeyword(m.groupValues[1])
            if (victim != null) {
                showStatus("🗑 Deleted: ${victim.title.take(40)}", 3000)
                GroqSpeech.speak(this, "Deleted ${victim.keywords.firstOrNull() ?: "bookmark"}.") {}
            } else showStatus("No bookmark matches \"${m.groupValues[1]}\"", 3000)
            return
        }

        if (Regex("^(open|show) (my )?bookmarks?( manager| page)?$").matches(text)) {
            showBookmarksPage(); return
        }

        // Anchored: "refresh my memory on this article" used to reload the page,
        // destroying scroll position and the injected agent. Bare "refresh" must
        // keep working — the app's own recovery hint tells the user to say it.
        if (Regex("^(?:refresh|reload)(?: (?:this|the) page| page)?$").matches(text)) {
            showStatus("⟳ Refreshing", 2000)
            webView.reload(); return
        }

        Regex("^(?:open|go to|goto|load) (.+)$").find(text)?.let { m ->
            val bm = bookmarks.bestMatch(m.groupValues[1])
            if (bm != null) {
                showStatus("→ ${bm.title.take(44)}", 2500)
                webView.loadUrl(bm.url)
                return
            }
        }
        // Cancel a running agent. ANCHORED to the whole utterance on purpose:
        // a contains() version would swallow "stop the video", "cancel my
        // order" and "stop sharing", all of which are legitimate agent tasks.
        // Gated on agentRunning so these stay available as tasks otherwise.
        if (agentRunning &&
            Regex("^(?:stop|cancel|abort|never ?mind|forget it|quit)(?: it| that| the agent| the task| task)?$")
                .matches(text)
        ) {
            // Clear the pending ask BEFORE closing: __svAgentClose aborts the
            // resolver, after which a late __svAnswer is a silent no-op.
            pendingAskId = null
            clearAgentWatchdog()
            agentVisible = false
            webView.evaluateJavascript("try{window.__svAgentClose&&window.__svAgentClose()}catch(e){}", null)
            GroqSpeech.stopSpeaking()
            showStatus("Agent stopped", 2000)
            return
        }

        // A bare utterance that strongly matches a bookmark opens it — but
        // only a SHORT one. A whole spoken sentence accumulating stray points
        // used to navigate away mid-thought and destroy the agent session, so
        // this path now demands few words, a higher score, and a whole-keyword
        // hit. Explicit "open <x>" above keeps the permissive threshold.
        if (BookmarkStore.tokenize(text).size <= 3) {
            bookmarks.bestMatch(text, minScore = 6, requireExact = true)?.let { bm ->
                showStatus("→ ${bm.title.take(44)}", 2500)
                webView.loadUrl(bm.url)
                return
            }
        }

        // Default: hand the request to page-agent on the current page.
        sendAgentTask(raw.trim())
    }

    // ------------------------------------------------------------------
    //  page-agent (alibaba/page-agent) — Groq-backed page automation
    // ------------------------------------------------------------------

    private fun injectPolyfills() {
        val js = """
            (function(){
              if (window.__svPoly) return; window.__svPoly = true;
              function def(o,n,f){ try{ if(!o[n]) Object.defineProperty(o,n,{value:f,writable:true,configurable:true}); }catch(e){} }
              // Align JS-visible browser signals with the spoofed modern UA so
              // Cloudflare Turnstile doesn't detect an old/automated engine and loop.
              try{ Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true}); }catch(e){}
              try{
                if (navigator.userAgentData){
                  var brands=[{brand:'Chromium',version:'125'},{brand:'Google Chrome',version:'125'},{brand:'Not.A/Brand',version:'24'}];
                  Object.defineProperty(navigator.userAgentData,'brands',{get:function(){return brands;},configurable:true});
                }
              }catch(e){}
              def(Array.prototype,'findLastIndex',function(cb,th){ for(var i=this.length-1;i>=0;i--){ if(cb.call(th,this[i],i,this)) return i; } return -1; });
              def(Array.prototype,'findLast',function(cb,th){ for(var i=this.length-1;i>=0;i--){ if(cb.call(th,this[i],i,this)) return this[i]; } });
              def(Array.prototype,'toSorted',function(c){ return this.slice().sort(c); });
              def(Array.prototype,'toReversed',function(){ return this.slice().reverse(); });
              def(Array.prototype,'with',function(i,v){ var a=this.slice(); a[i<0?a.length+i:i]=v; return a; });
              def(Promise,'withResolvers',function(){ var res,rej,p=new Promise(function(a,b){res=a;rej=b;}); return {promise:p,resolve:res,reject:rej}; });
              def(Object,'groupBy',function(items,cb){ var o=Object.create(null),i=0; [].slice.call(items).forEach(function(it){ var k=cb(it,i++); (o[k]=o[k]||[]).push(it); }); return o; });
              if (typeof structuredClone!=='function'){ window.structuredClone=function sc(v,seen){ seen=seen||new Map(); if(v===null||typeof v!=='object') return v; if(seen.has(v)) return seen.get(v); if(v instanceof Date) return new Date(v.getTime()); if(v instanceof RegExp) return new RegExp(v.source,v.flags); if(v instanceof Map){var m=new Map();seen.set(v,m);v.forEach(function(val,k){m.set(sc(k,seen),sc(val,seen));});return m;} if(v instanceof Set){var s=new Set();seen.set(v,s);v.forEach(function(val){s.add(sc(val,seen));});return s;} if(Array.isArray(v)){var a=[];seen.set(v,a);for(var i=0;i<v.length;i++)a[i]=sc(v[i],seen);return a;} var o={};seen.set(v,o);Object.keys(v).forEach(function(k){o[k]=sc(v[k],seen);});return o; }; }
              if (typeof reportError!=='function'){ try{ window.reportError=function(e){ try{console.error(e);}catch(_){} }; }catch(e){} }
              if (typeof AbortSignal!=='undefined'){
                if (!AbortSignal.prototype.throwIfAborted){
                  Object.defineProperty(AbortSignal.prototype,'throwIfAborted',{value:function(){ if(this.aborted) throw (this.reason||new DOMException('Aborted','AbortError')); },writable:true,configurable:true});
                }
                def(AbortSignal,'timeout',function(ms){ var c=new AbortController(); setTimeout(function(){ try{ c.abort(new DOMException('TimeoutError','TimeoutError')); }catch(e){ c.abort(); } },ms); return c.signal; });
                def(AbortSignal,'any',function(sigs){ var c=new AbortController(); [].slice.call(sigs).forEach(function(s){ if(s.aborted){ try{ c.abort(s.reason); }catch(e){ c.abort(); } } else s.addEventListener('abort',function(){ try{ c.abort(s.reason); }catch(e){ c.abort(); } }); }); return c.signal; });
              }
              def(Array.prototype,'at',function(i){ i=Math.trunc(i)||0; if(i<0) i+=this.length; return (i<0||i>=this.length)?undefined:this[i]; });
              def(String.prototype,'at',function(i){ i=Math.trunc(i)||0; if(i<0) i+=this.length; return (i<0||i>=this.length)?undefined:this[i]; });
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    /** Force a dark render so pages sit on black (waveguide off), like TapLink. */
    /**
     * Collapse the empty slots that host-blocking leaves behind.
     *
     * Network blocking kills the ad but not the same-origin container reserving
     * space for it, so pages end up with tall blank gaps — worse on a waveguide
     * than the ad was.
     *
     * Scope is deliberately narrow: unambiguous ad-tech markup ONLY. A generic
     * "big fixed overlay with little text" heuristic was considered and rejected
     * — it matches page-agent's own simulator mask exactly (position:fixed,
     * inset:0, z-index:2147483641, negligible text), and on a device whose only
     * input is tap counts, a false positive that eats a consent dialog or login
     * modal is unrecoverable in the field.
     *
     * Hides via visibility/height rather than removing nodes: page-agent reads
     * the DOM as indexed text, and deleting elements changes what it can act on.
     */
    private fun injectCosmeticFilter() {
        val js = """
            (function(){
              var AGENT = '#page-agent-runtime_agent-panel,#page-agent-runtime_simulator-mask';
              if (!window.__svCos){
                window.__svCos = true;
                var s = document.createElement('style');
                s.id = '__svCosStyle';
                s.textContent =
                  'ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],[class*="ad-slot"],' +
                  '[data-ad-client],[data-ad-slot],[data-adunit],iframe[src*="doubleclick"],' +
                  'iframe[src*="googlesyndication"],iframe[src*="amazon-adsystem"]' +
                  '{display:none!important}';
                (document.head||document.documentElement).appendChild(s);
              }
              // Sweep separately from the stylesheet so it can re-run on SPA
              // route changes; collapsing only AFTER an element has measured
              // zero avoids nuking slots that fill in late.
              function sweep(){
                var sel = 'ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],[class*="ad-slot"]';
                var n = document.querySelectorAll(sel);
                for (var i=0;i<n.length;i++){
                  var e = n[i];
                  if (e.closest && e.closest(AGENT)) continue;
                  var p = e.parentElement;
                  if (p && p.children.length === 1){
                    var r = p.getBoundingClientRect();
                    if (r.height > 40 && (p.innerText||'').trim().length < 8){
                      p.style.setProperty('height','0','important');
                      p.style.setProperty('min-height','0','important');
                      p.style.setProperty('overflow','hidden','important');
                    }
                  }
                }
              }
              sweep();
              if (!window.__svCosObs){
                var t = null;
                window.__svCosObs = new MutationObserver(function(){
                  if (t) return;
                  t = setTimeout(function(){ t = null; sweep(); }, 500);
                });
                window.__svCosObs.observe(document.documentElement, {childList:true, subtree:true});
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectDarkMode() {
        val js = """
            (function(){
              if (!window.__svDark){
                window.__svDark = true;
                try{ ['theme','color-theme','ui-theme','ddg_theme'].forEach(function(k){ localStorage.setItem(k,'dark'); }); }catch(e){}
                try{
                  var mm = window.matchMedia ? window.matchMedia.bind(window) : null;
                  if (mm){
                    window.matchMedia = function(q){
                      var qq = String(q);
                      if (qq.indexOf('prefers-color-scheme') >= 0){
                        var dark = qq.indexOf('dark') >= 0;
                        return { matches:dark, media:qq, onchange:null, addListener:function(){}, removeListener:function(){}, addEventListener:function(){}, removeEventListener:function(){}, dispatchEvent:function(){ return false; } };
                      }
                      return mm(q);
                    };
                  }
                }catch(e){}
                try{ var de=document.documentElement; de.style.colorScheme='dark';
                  if(!document.getElementById('sv-dark')){ var s=document.createElement('style'); s.id='sv-dark';
                    s.textContent=':root{color-scheme:dark!important}'; (document.head||de).appendChild(s); } }catch(e){}
              }
              // Force black on pages that ignore the dark-scheme hint: sample the
              // effective background and, if it's light, apply a smart-invert filter
              // (images/video/page-agent panel are re-inverted so they look normal).
              function parseRgb(s){ var m=/rgba?\(([^)]+)\)/.exec(s||''); if(!m) return null;
                var p=m[1].split(',').map(function(x){return parseFloat(x);});
                if(p.length>=4 && p[3]===0) return null; return p; }
              function effBg(){ var els=[document.body, document.documentElement];
                for(var i=0;i<els.length;i++){ if(!els[i]) continue;
                  var c=parseRgb(getComputedStyle(els[i]).backgroundColor); if(c) return c; } return null; }
              function isLight(c){ if(!c) return true; return (0.299*c[0]+0.587*c[1]+0.114*c[2])>140; }
              function applyInvert(on){ var ex=document.getElementById('sv-invert');
                if(on){ if(ex) return; var s=document.createElement('style'); s.id='sv-invert';
                  s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#0a0a0a!important}'+
                    'img,video,picture,canvas,svg,iframe,embed,object,[style*="url("],[class*="page-agent" i]{filter:invert(1) hue-rotate(180deg)!important}';
                  (document.head||document.documentElement).appendChild(s);
                } else if(ex){ ex.remove(); } }
              function check(){ try{ applyInvert(isLight(effBg())); }catch(e){} }
              check();
              if(!window.__svDarkTimers){ window.__svDarkTimers=true;
                [250,800,1800].forEach(function(t){ setTimeout(check,t); }); }
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    /** Load the bundled page-agent and point it at the active LLM. Panel starts hidden. */
    private fun injectPageAgent() {
        val bundle = pageAgentJs ?: return
        val provider = GroqSpeech.agentProvider(this)
        val key = GroqSpeech.agentKey(this)
        if (key.isEmpty()) return
        // The demo bundle auto-inits against its test LLM when document.currentScript
        // is null (our evaluateJavascript case). Spoof currentScript with
        // ?autoInit=false around the bundle eval to keep it inert, then init ourselves.
        val spoof = "try{Object.defineProperty(document,'currentScript',{configurable:true," +
            "get:function(){return {src:'https://smartview.local/page-agent.js?autoInit=false'};}});}catch(e){}"
        val unspoof = "try{delete document.currentScript;}catch(e){}"
        // Route page-agent's LLM-provider fetches through the native bridge so the
        // loaded page's CSP (connect-src) can't block them. Installed before the
        // bundle eval in case page-agent captures window.fetch at load time.
        val hostsJs = GroqSpeech.AGENT_HOSTS.joinToString(",") { JSONObject.quote(it) }
        val fetchProxy = """
            (function(){
              if (window.__svFetchProxyInstalled) return; window.__svFetchProxyInstalled = true;
              var HOSTS = [$hostsJs];
              function isAgentUrl(u){ for (var i=0;i<HOSTS.length;i++){ if (u.indexOf(HOSTS[i]) >= 0) return true; } return false; }
              var seq = 0, pending = {};
              window.__svFetchResolve = function(id, status, ok, b64){
                var p = pending[id]; if(!p) return; delete pending[id];
                var body = '';
                try { body = decodeURIComponent(escape(atob(b64))); }
                catch(e){ try{ body = atob(b64); }catch(e2){ body = ''; } }
                try { p.resolve(new Response(body, {status: status||200, statusText: ok?'OK':'ERR'})); }
                catch(e){ p.reject(new TypeError('resp: ' + e)); }
              };
              window.__svFetchReject = function(id, msg){
                var p = pending[id]; if(!p) return; delete pending[id];
                p.reject(new TypeError(msg || 'network request failed'));
              };
              var orig = window.fetch ? window.fetch.bind(window) : null;
              window.fetch = function(input, init){
                var url = (typeof input === 'string') ? input : (input && input.url) || '';
                if (!isAgentUrl(url) && orig) return orig(input, init);
                init = init || {};
                var method = init.method || (typeof input==='object' && input && input.method) || 'GET';
                var headers = {};
                try {
                  var h = init.headers || (typeof input==='object' && input && input.headers);
                  if (h){
                    if (typeof h.forEach === 'function'){ h.forEach(function(v,k){ headers[k]=v; }); }
                    else { Object.keys(h).forEach(function(k){ headers[k]=h[k]; }); }
                  }
                } catch(e){}
                var body = init.body;
                if (body != null && typeof body !== 'string'){ try{ body = String(body); }catch(e){ body=''; } }
                return new Promise(function(resolve, reject){
                  var id = 'f' + (++seq); pending[id] = {resolve:resolve, reject:reject};
                  try { SvBridge.llmFetch(id, url, String(method).toUpperCase(), JSON.stringify(headers), body||''); }
                  catch(e){ delete pending[id]; reject(new TypeError('bridge: ' + e)); }
                });
              };
            })();
        """.trimIndent()
        val init = """
            (function(){
              try{
                if (window.__svAgentTask) return; window.__svAgentInit = true;
                try{ if(window.pageAgent && window.pageAgent.dispose) window.pageAgent.dispose(); }catch(e){}
                window.pageAgent = new window.PageAgent({
                  model: ${JSONObject.quote(provider.model)},
                  baseURL: ${JSONObject.quote(provider.baseUrl)},
                  apiKey: ${JSONObject.quote(key)},
                  language: 'en-US'
                });
                try{ if(window.pageAgent.panel && window.pageAgent.panel.hide) window.pageAgent.panel.hide(); }catch(e){}
                // Route page-agent's ask_user tool through native voice: read the
                // question via TTS, then auto-enable STT for the spoken answer.
                window.__svAskResolvers = {};
                window.__svAnswer = function(id, text){
                  var r = window.__svAskResolvers[id]; if(!r) return;
                  delete window.__svAskResolvers[id]; try{ r.resolve(String(text||'')); }catch(e){}
                };
                var svAsk = function(question, opts){
                  return new Promise(function(resolve, reject){
                    var id = 'q' + (Date.now()) + '_' + Math.floor(Math.random()*1e6);
                    window.__svAskResolvers[id] = {resolve:resolve, reject:reject};
                    try{
                      var sig = opts && opts.signal;
                      if (sig){
                        if (sig.aborted){ delete window.__svAskResolvers[id]; reject(new DOMException('Aborted','AbortError')); return; }
                        sig.addEventListener('abort', function(){
                          var r = window.__svAskResolvers[id];
                          if (r){ delete window.__svAskResolvers[id]; r.reject(new DOMException('Aborted','AbortError')); }
                        });
                      }
                      SvBridge.onAgentAsk(id, String(question || ''));
                    }catch(e){ delete window.__svAskResolvers[id]; reject(e); }
                  });
                };
                try{ window.pageAgent.onAskUser = svAsk; }catch(e){}
                try{ if(window.pageAgent.core) window.pageAgent.core.onAskUser = svAsk; }catch(e){}
                window.__svAgentRoot = function(){
                  return document.querySelector('[class*="page-agent" i], [id*="page-agent" i], page-agent') ||
                    (window.pageAgent && window.pageAgent.panel && (window.pageAgent.panel.element || window.pageAgent.panel.dom || window.pageAgent.panel.container)) || null;
                };
                window.__svPanelText = '';
                setInterval(function(){
                  try{
                    var r = window.__svAgentRoot(); if(!r || !r.innerText) return;
                    var t = r.innerText.replace(/\s+/g,' ').trim();
                    if (t && t !== window.__svPanelText) window.__svPanelText = t;
                  }catch(e){}
                }, 900);
                function isCloseBtn(b){
                  var lbl = ((b.innerText||'') + ' ' + (b.getAttribute('aria-label')||'') + ' ' + (b.title||'')).trim().toLowerCase();
                  return lbl === 'x' || lbl === '×' || lbl === '✕' || lbl.indexOf('close') >= 0;
                }
                // Closing the panel must stop the running task immediately and tell
                // native to drop out of any agent voice loop.
                window.__svAgentClose = function(){
                  try{ if(window.pageAgent.stop) window.pageAgent.stop(); }catch(e){}
                  try{ if(window.pageAgent.core && window.pageAgent.core.stop) window.pageAgent.core.stop(); }catch(e){}
                  try{ window.pageAgent.panel.hide(); }catch(e){}
                  try{ SvBridge.onAgentClosed(); }catch(e){}
                };
                // Delegated capture handler: any click on the panel's close button
                // closes immediately, even if page-agent recreates the button.
                if(!window.__svCloseHook){ window.__svCloseHook = true;
                  document.addEventListener('click', function(e){
                    try{
                      var r = window.__svAgentRoot(); if(!r) return;
                      var t = e.target; if(!r.contains(t)) return;
                      var b = t.closest ? t.closest('button,[role="button"]') : null;
                      if (b && isCloseBtn(b)){ setTimeout(window.__svAgentClose, 0); }
                    }catch(_){}
                  }, true);
                }
                window.__svAgentShow = function(task){
                  try{ window.pageAgent.panel.show(); }catch(e){}
                  setTimeout(function(){
                    try{
                      var r = window.__svAgentRoot(); if(!r) return;
                      // Do NOT focus the panel input — page-agent is voice-driven and
                      // focusing it would pop the on-screen keyboard.
                      var btns = r.querySelectorAll('button, [role="button"]');
                      for (var i=0;i<btns.length;i++){
                        var b = btns[i];
                        if (isCloseBtn(b)){
                          b.style.transform = 'scale(2)';         /* close button 2x size */
                          b.style.transformOrigin = 'center';
                          b.style.zIndex = '2147483647';
                        }
                      }
                    }catch(e){}
                  }, 300);
                };
                window.__svAgentTask = function(task){
                  try{
                    // A second task while one is running used to reach
                    // execute(), which throws "A task is already running" —
                    // surfacing as a spoken "Agent error" while the panel had
                    // already swapped to the new task and the old one kept
                    // stepping invisibly. Refuse explicitly instead. Still call
                    // __svAgentShow: it is the only thing that brings the panel
                    // (and its cancel control) back after a hide.
                    var st = '';
                    try{ st = window.pageAgent.status || ''; }catch(e){}
                    if (st === 'running'){
                      window.__svAgentShow(task);
                      SvBridge.onAgentBusy(String(task));
                      return;
                    }
                    window.__svAgentShow(task);
                    Promise.resolve(window.pageAgent.execute(task)).then(function(res){
                      // execute() RESOLVES for LLM errors, step-limit
                      // exhaustion and user abort — only disposal/duplicate/
                      // empty-task reject. So success has to be read off the
                      // result, not inferred from "did not throw". The old code
                      // probed message/summary/result/text (none of which
                      // exist) and then read a mid-word tail of scraped panel
                      // innerText aloud, emoji and button labels included.
                      var ok = !!(res && res.success);
                      var msg = (res && res.data) || '';
                      if (ok) SvBridge.onAgentDone(String(msg || 'Task finished.'));
                      else SvBridge.onAgentError(String(msg || 'Task did not complete.'));
                    }).catch(function(e){
                      SvBridge.onAgentError(String((e && e.message) || e));
                    });
                  }catch(e){ SvBridge.onAgentError(String((e && e.message) || e)); }
                };
                window.__svAgentHide = function(){ try{ window.pageAgent.panel.hide(); }catch(e){} };
                SvBridge.onAgentReady();
              }catch(e){ SvBridge.onAgentError('init: ' + String((e && e.message) || e)); }
            })();
        """.trimIndent()
        runCatching {
            webView.evaluateJavascript(spoof, null)
            webView.evaluateJavascript(fetchProxy, null)
            webView.evaluateJavascript(bundle, null)
            webView.evaluateJavascript(unspoof, null)
            webView.evaluateJavascript(init, null)
        }
    }

    private fun sendAgentTask(task: String) {
        if (GroqSpeech.agentKey(this).isEmpty()) {
            val p = GroqSpeech.agentProvider(this)
            showStatus("No ${p.label} key — triple-tap to open Settings", 5000)
            GroqSpeech.speak(this, "No agent key set. Triple tap to open settings and add your ${p.label} key.") {}
            return
        }
        if (webView.url?.startsWith(INTERNAL_BASE) == true) {
            showStatus("Open a web page first, then use the agent", 4000)
            return
        }
        dispatchAgentTask(task, retry = true)
    }

    /**
     * Run an agent task, re-injecting page-agent if it isn't loaded on this page
     * (e.g. the page was opened while offline, so the error page ate the injection).
     */
    /**
     * Idle watchdog for a running agent task.
     *
     * Deliberately IDLE-based, not a wall clock: a legitimate multi-step run
     * takes minutes, and a total cap would kill healthy tasks. Rearmed on every
     * sign of life (LLM response, a question, a panel-text change), so it only
     * fires on true silence.
     *
     * This is also the only recovery from the navigation-death case, where the
     * page that held the agent is gone and asking it to stop is a no-op —
     * observed as "Thinking…" forever with no way out.
     */
    // Explicit type: the body re-posts itself while an ask is pending, and
    // Kotlin cannot infer the type of a self-referential declaration.
    private val agentWatchdog: Runnable = Runnable {
        if (!agentRunning) return@Runnable
        // The agent is allowed to sit silent while it is waiting on a HUMAN.
        // ask_user legitimately spans: speak the question, open the mic for up
        // to 8s, Whisper round trip, and however long the wearer takes to
        // answer. This watchdog exists to catch a dead AGENT, so while an ask
        // is outstanding it re-arms instead of firing. (Caught in testing: the
        // first version killed a perfectly healthy task mid-question and told
        // the user the agent had stopped responding.)
        if (pendingAskId != null) {
            main.postDelayed(agentWatchdog, AGENT_IDLE_MS)
            return@Runnable
        }
        Log.w(TAG, "agent watchdog: no activity for ${AGENT_IDLE_MS}ms — recovering")
        agentRunning = false
        agentVisible = false
        pendingAskId = null
        runCatching { webView.evaluateJavascript("try{window.__svAgentClose&&window.__svAgentClose()}catch(e){}", null) }
        showStatus("Agent stopped responding — say \"refresh\" to reload", 5000)
        GroqSpeech.speak(this, "The agent stopped responding.") {}
    }

    private fun armAgentWatchdog() {
        main.removeCallbacks(agentWatchdog)
        if (agentRunning) main.postDelayed(agentWatchdog, AGENT_IDLE_MS)
    }

    private fun clearAgentWatchdog() {
        agentRunning = false
        main.removeCallbacks(agentWatchdog)
    }

    private fun dispatchAgentTask(task: String, retry: Boolean) {
        webView.evaluateJavascript("(typeof window.__svAgentTask==='function')") { r ->
            when {
                r == "true" -> {
                    cancelAgentHide()
                    agentVisible = true
                    // Armed only here, in the branch that actually starts a
                    // task — not in the retry recursion below, which would
                    // otherwise start a timer for a task that never ran.
                    agentRunning = true
                    armAgentWatchdog()
                    showStatus("🤖 " + task.take(48), 3000)
                    webView.evaluateJavascript("window.__svAgentTask(${JSONObject.quote(task)})", null)
                }
                retry -> {
                    showStatus("Starting agent…", 2000)
                    injectPageAgent()
                    main.postDelayed({ dispatchAgentTask(task, retry = false) }, 800)
                }
                else -> showStatus("Agent unavailable here — say \"refresh\" to reload", 4500)
            }
        }
    }

    /** Resolve page-agent's pending ask_user question with the spoken answer. */
    private fun answerAgent(text: String) {
        val id = pendingAskId ?: return
        // Answer delivered: the agent owns the clock again.
        armAgentWatchdog()
        pendingAskId = null
        js("window.__svAnswer && window.__svAnswer(${JSONObject.quote(id)}, ${JSONObject.quote(text)})")
    }

    private fun scheduleAgentHide() {
        cancelAgentHide()
        main.postDelayed(agentHideRunnable, AGENT_HIDE_MS)
    }

    private fun cancelAgentHide() = main.removeCallbacks(agentHideRunnable)

    private fun hideAgentPanel() {
        agentVisible = false
        webView.evaluateJavascript("window.__svAgentHide && window.__svAgentHide()", null)
    }

    /** JS bridge: page-agent events + keyboard focus + internal pages. */
    private inner class SvBridge {
        @JavascriptInterface fun onInputFocus(value: String?) = runOnUiThread {
            // Don't pop the keyboard when page-agent is driving inputs itself.
            if (agentVisible) return@runOnUiThread
            suppressImeFor(1800L); showKeyboard()
        }
        @JavascriptInterface fun onInputBlur() = runOnUiThread { hideSystemKeyboard() }

        @JavascriptInterface fun onAgentReady() = runOnUiThread {
            Log.d(TAG, "page-agent ready on ${webView.url}")
        }
        @JavascriptInterface fun onAgentDone(message: String) = runOnUiThread {
            clearAgentWatchdog()
            if (!agentVisible) return@runOnUiThread
            cancelAgentHide()
            showStatus("🤖 " + message.take(60), 4000)
            // Task is finished: read the result, then let the panel auto-hide.
            // (No auto-relisten — that caused a run→listen→run loop. Double-tap
            // again to issue a new task; mid-task questions use onAgentAsk.)
            GroqSpeech.speak(this@MainActivity, message) { scheduleAgentHide() }
        }
        /** page-agent asked the user a question (ask_user tool). Read it, then listen. */
        @JavascriptInterface fun onAgentAsk(id: String, question: String) = runOnUiThread {
            armAgentWatchdog()
            agentVisible = true
            cancelAgentHide()
            pendingAskId = id
            showStatus("❓ " + question.take(60), 6000)
            GroqSpeech.speak(this@MainActivity, question) {
                // Only auto-listen if this question is still the outstanding one.
                if (pendingAskId == id) startVoice(VoiceMode.AGENT_ANSWER)
            }
        }
        /** User clicked the panel's ✕ — tear down the agent interaction at once. */
        @JavascriptInterface fun onAgentClosed() = runOnUiThread {
            clearAgentWatchdog()
            agentVisible = false
            cancelAgentHide()
            pendingAskId = null
            if (voiceMode == VoiceMode.AGENT_ANSWER) {
                main.removeCallbacks(autoStopRecording)
                if (recorder.isRecording) recorder.stop()
                voiceMode = VoiceMode.NONE
            }
            GroqSpeech.stopSpeaking()
            showStatus("Agent closed", 1500)
        }
        @JavascriptInterface fun onAgentError(message: String) = runOnUiThread {
            clearAgentWatchdog()
            // Ignore the abort that fires after the user closed the panel.
            if (!agentVisible) return@runOnUiThread
            Log.w(TAG, "page-agent error: $message")
            showStatus("🤖 error: " + message.take(60), 4000)
            if (agentVisible) {
                GroqSpeech.speak(this@MainActivity, "Agent error. $message") { scheduleAgentHide() }
            }
        }

        /** A task was refused because one is already running. */
        @JavascriptInterface fun onAgentBusy(task: String) = runOnUiThread {
            armAgentWatchdog()
            agentVisible = true
            cancelAgentHide()
            showStatus("Still working — say \"stop\" to cancel", 4000)
            GroqSpeech.speak(this@MainActivity, "Still working on the previous task. Say stop to cancel.") {}
        }

        /** page-agent fetch proxy: perform the Groq call natively (bypasses page CSP). */
        @JavascriptInterface fun llmFetch(id: String, url: String, method: String, headersJson: String, body: String) {
            val safeId = id.replace(Regex("[^A-Za-z0-9]"), "")
            runOnUiThread { armAgentWatchdog() }
            // DIAG: measure page-agent request size (~chars/4 ≈ tokens).
            Log.d(TAG, "llmFetch REQ bytes=${body.length} ~${body.length / 4}tok")
            GroqSpeech.rawRequest(url, method, headersJson, body) { code, ok, bytes ->
                // DIAG: on a rate-limit/error, Groq's body states limit vs requested tokens.
                if (!ok) Log.w(TAG, "llmFetch RESP $code: ${String(bytes).take(500)}")
                runOnUiThread {
                    if (code == 0) {
                        val msg = String(bytes).replace("\\", "\\\\").replace("'", "\\'")
                            .replace("\n", " ").replace("\r", " ").take(180)
                        webView.evaluateJavascript(
                            "window.__svFetchReject&&window.__svFetchReject('$safeId','$msg')", null)
                    } else {
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        webView.evaluateJavascript(
                            "window.__svFetchResolve&&window.__svFetchResolve('$safeId',$code,$ok,'$b64')", null)
                    }
                }
            }
        }

        @JavascriptInterface fun openUrl(url: String) = runOnUiThread { webView.loadUrl(url) }
        @JavascriptInterface fun deleteBookmarkUrl(url: String) = runOnUiThread {
            bookmarks.deleteByUrl(url); showBookmarksPage()
        }
        /** Settings save: agent provider selection + any non-blank keys entered. */
        @JavascriptInterface fun saveSettings(
            provider: String, groqKey: String, geminiKey: String, cerebrasKey: String
        ) = runOnUiThread {
            GroqSpeech.setAgentProvider(this@MainActivity, provider)
            if (groqKey.isNotBlank()) GroqSpeech.setProviderKey(this@MainActivity, "groq_api_key", groqKey)
            if (geminiKey.isNotBlank()) GroqSpeech.setProviderKey(this@MainActivity, "gemini_api_key", geminiKey)
            if (cerebrasKey.isNotBlank()) GroqSpeech.setProviderKey(this@MainActivity, "cerebras_api_key", cerebrasKey)
            val p = GroqSpeech.agentProvider(this@MainActivity)
            val ready = GroqSpeech.agentKey(this@MainActivity).isNotEmpty()
            showStatus(if (ready) "Agent: ${p.label} ✓" else "Agent: ${p.label} — add a key", 3500)
            showBookmarksPage()
        }
    }

    // ------------------------------------------------------------------
    //  Bookmark manager page (internal)
    // ------------------------------------------------------------------

    private fun showBookmarksPage() {
        val rows = bookmarks.all().joinToString("") { bm ->
            val kws = bm.keywords.joinToString(", ")
            val u = bm.url.replace("'", "%27")
            """<div class="bm">
                 <div class="bmtxt" onclick="SvBridge.openUrl('$u')">
                   <div class="t">${escapeHtml(bm.title)}</div>
                   <div class="k">${escapeHtml(kws)}</div>
                 </div>
                 <button class="del" onclick="SvBridge.deleteBookmarkUrl('$u')">✕</button>
               </div>"""
        }
        val sp = getSharedPreferences("smartview", Context.MODE_PRIVATE)
        fun saved(pref: String) = sp.getString(pref, "").orEmpty().isNotEmpty()
        val active = GroqSpeech.agentProvider(this)
        fun badge(pref: String) = if (saved(pref)) "<span class=\"sv\">✓ saved</span>" else "<span class=\"nk\">no key</span>"
        // Radio + key field per agent provider (Groq's key doubles as the voice key).
        val provRows = GroqSpeech.AGENT_PROVIDERS.joinToString("") { p ->
            val checked = if (p.id == active.id) "checked" else ""
            val note = if (p.id == "groq") "<div class=\"pn\">Reuses the Groq voice key below</div>" else
                "<input class=\"pk\" id=\"k_${p.id}\" type=\"text\" placeholder=\"${p.keyHint}\" autocomplete=\"off\">"
            """<label class="prov">
                 <input type="radio" name="prov" value="${p.id}" $checked>
                 <span class="pl">${escapeHtml(p.label)}</span> ${badge(p.keyPref)}
               </label>
               $note"""
        }
        // Snapshot status line: time · date · battery · network.
        val now = java.util.Date()
        val timeStr = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(now)
        val dateStr = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(now)
        val batt = batteryPct()
        val battStr = if (batt in 0..100) "$batt%" else "—"
        val online = isOnline()
        val netStr = if (online) "📶 ${networkLabel()}" else "📵 Offline"
        val netClass = if (online) "net-ok" else "net-bad"
        val statusBar = """
            <div class="statusbar">
              <span>🕒 $timeStr</span>
              <span>$dateStr</span>
              <span>🔋 $battStr</span>
              <span class="$netClass">$netStr</span>
            </div>
        """.trimIndent()
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{background:#000;color:#e6edf3;font-family:sans-serif;margin:0;padding:14px}
              .statusbar{display:flex;justify-content:space-between;align-items:center;gap:8px;
                   background:linear-gradient(90deg,#0d1117,#161b22);border:1px solid #21262d;border-radius:10px;
                   padding:9px 14px;font-size:12.5px;color:#adbac7;margin-bottom:14px;letter-spacing:.2px;white-space:nowrap}
              .statusbar .net-ok{color:#3fb950;font-weight:600} .statusbar .net-bad{color:#f85149;font-weight:600}
              h2{margin:14px 0 8px;font-size:19px} .sub{color:#8b949e;font-size:12px;margin-bottom:10px}
              .bm{display:flex;align-items:center;background:#0d1117;border:1px solid #30363d;border-radius:10px;margin:8px 0}
              .bmtxt{flex:1;padding:10px 12px} .t{font-size:15px} .k{color:#7d8590;font-size:11px;margin-top:3px}
              .del{background:#161b22;color:#f85149;border:1px solid #30363d;border-radius:8px;font-size:18px;
                   width:44px;height:44px;margin:6px}
              input[type=text]{width:100%;box-sizing:border-box;background:#0d1117;color:#e6edf3;border:1px solid #30363d;
                   border-radius:8px;padding:10px;font-size:13px;margin:4px 0 10px}
              .active{background:#0d2818;border:1px solid #238636;border-radius:8px;padding:8px 10px;font-size:13px;color:#3fb950;margin-bottom:10px}
              .prov{display:flex;align-items:center;gap:8px;margin-top:8px;font-size:15px}
              .prov input[type=radio]{width:20px;height:20px}
              .pl{font-weight:600} .pn{color:#7d8590;font-size:11px;margin:2px 0 8px 28px}
              .sv{color:#3fb950;font-size:11px} .nk{color:#8b949e;font-size:11px}
              .save{background:#238636;color:#fff;border:0;border-radius:8px;padding:12px 16px;font-size:15px;width:100%;margin-top:8px}
              .empty{color:#7d8590;padding:18px 4px}
              hr{border:0;border-top:1px solid #21262d;margin:16px 0}
            </style></head><body>
            $statusBar
            <h2>★ Bookmarks</h2>
            <div class="sub">Triple-tap opens this page · say "open &lt;keyword&gt;" / "delete &lt;keyword&gt; bookmark" · pull the left edge to exit</div>
            ${if (rows.isEmpty()) "<div class=\"empty\">No bookmarks yet — double-tap and say \"bookmark this web page\".</div>" else rows}
            <hr>
            <h2>Agent LLM</h2>
            <div class="active">● Active: ${escapeHtml(active.label)} · <code>${escapeHtml(active.model)}</code></div>
            <div class="sub">page-agent needs a big token budget. Gemini free = 1M tok/min (recommended). Groq free = 8k/min (too small for multi-step tasks).</div>
            $provRows
            <hr>
            <h2>Groq key <span class="nk">(voice: STT + TTS)</span></h2>
            <div class="sub">Whisper transcription + Orpheus speech. ${badge("groq_api_key")}</div>
            <input id="k_groq" type="text" placeholder="gsk_…" autocomplete="off">
            <button class="save" onclick="SvBridge.saveSettings(
              (document.querySelector('input[name=prov]:checked')||{}).value||'gemini',
              document.getElementById('k_groq').value,
              (document.getElementById('k_gemini')||{}).value||'',
              (document.getElementById('k_cerebras')||{}).value||'')">Save settings</button>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(INTERNAL_BASE, html, "text/html", "utf-8", null)
        showStatus("Settings", 1500)
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ------------------------------------------------------------------
    //  Status pill (mirrored to both eyes automatically)
    // ------------------------------------------------------------------

    private fun showStatus(text: String, autoHideMs: Long) {
        statusPill.text = text
        statusPill.visibility = View.VISIBLE
        main.removeCallbacks(statusHideRunnable)
        if (autoHideMs > 0) main.postDelayed(statusHideRunnable, autoHideMs)
    }

    // ------------------------------------------------------------------
    //  On-screen keyboard (TapGarden lineage) + Groq dictation mic key
    // ------------------------------------------------------------------

    private fun disableSystemKeyboard(wv: WebView) {
        runCatching {
            WebView::class.java.getMethod("setShowSoftInputOnFocus", java.lang.Boolean.TYPE)
                .invoke(wv, false)
        }
        wv.setOnFocusChangeListener { view, hasFocus -> if (hasFocus) hideSystemKeyboard(view) }
    }

    private fun suppressImeFor(durationMs: Long) {
        suppressImeUntilMs = System.currentTimeMillis() + durationMs
        hideSystemKeyboard()
        fun tick() {
            hideSystemKeyboard()
            if (System.currentTimeMillis() < suppressImeUntilMs) {
                imeSuppressor.postDelayed({ tick() }, 90L)
            }
        }
        imeSuppressor.removeCallbacksAndMessages(null)
        tick()
    }

    private fun hideSystemKeyboard(view: View = webView) {
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun injectKeyboardSupport() {
        val js = """
            (function(){
              if (window.__svHooked) return;
              if (!document.documentElement) return;
              window.__svHooked = true;
              function isInput(el){ return el && (el.tagName==='INPUT' || el.tagName==='TEXTAREA' || el.isContentEditable); }
              // page-agent's own input is voice-driven — never raise the keyboard for it.
              function inAgent(el){ try{ var r=window.__svAgentRoot&&window.__svAgentRoot(); return !!(r && el && r.contains(el)); }catch(e){ return false; } }
              try {
                var style=document.createElement('style');
                style.textContent='[data-sv-active="1"]{outline:2px solid #58a6ff!important;outline-offset:2px!important;} ::-webkit-scrollbar{display:none!important;width:0!important;height:0!important;} *{scrollbar-width:none!important;}';
                document.documentElement.appendChild(style);
              } catch(e) {}
              function markActive(el){
                document.querySelectorAll('[data-sv-active="1"]').forEach(function(n){ if(n!==el) n.removeAttribute('data-sv-active'); });
                try{ el.setAttribute('data-sv-active','1'); }catch(_){}
              }
              function remember(el){
                if(isInput(el) && !inAgent(el)){
                  window.__svActiveInput = el;
                  markActive(el);
                  try{ SvBridge.onInputFocus(typeof el.value === 'string' ? el.value : (el.textContent || '')); }catch(_){}
                }
              }
              function activeInput(){
                var el = window.__svActiveInput;
                if(!isInput(el) || !document.contains(el)) el = document.activeElement;
                if(!isInput(el)) return null;
                window.__svActiveInput = el;
                markActive(el);
                try{ el.focus({preventScroll:true}); }catch(e){ try{ el.focus(); }catch(_){} }
                return el;
              }
              function setNativeValue(el, value){
                var old = typeof el.value === 'string' ? el.value : '';
                var proto = Object.getPrototypeOf(el);
                var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
                if(!desc && el instanceof HTMLInputElement) desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
                if(!desc && el instanceof HTMLTextAreaElement) desc = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
                if(desc && desc.set) desc.set.call(el, value); else el.value = value;
                if(el._valueTracker){ try{ el._valueTracker.setValue(old); }catch(_){} }
              }
              function notify(el){
                try { el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:el.value})); }
                catch(e) { el.dispatchEvent(new Event('input',{bubbles:true})); }
                el.dispatchEvent(new Event('change',{bubbles:true}));
              }
              document.addEventListener('focusin', function(e){ remember(e.target); }, true);
              document.addEventListener('click', function(e){ if(isInput(e.target)) remember(e.target); }, true);
              document.addEventListener('focusout', function(e){ if(isInput(e.target)){ try{ SvBridge.onInputBlur(); }catch(_){} } }, true);
              // Video seek: YouTube/HTML5 scrub bars don't seek from our synthetic
              // tap (and YouTube may even suppress the click), so on a tap that
              // lands on a media progress/slider bar we set the video's currentTime
              // to the clicked fraction ourselves. Hook both pointerup and click.
              function svSeekFromEvent(e){
                try {
                  if (typeof e.clientX !== 'number') return;
                  var vids = document.getElementsByTagName('video');
                  if (!vids.length) return;
                  var bar = null;
                  for (var n = e.target; n && n !== document.documentElement; n = n.parentElement) {
                    if (!n.getAttribute) continue;
                    var role = (n.getAttribute('role') || '').toLowerCase();
                    var cls = (typeof n.className === 'string' ? n.className : '').toLowerCase();
                    if (role === 'slider' || /ytp-progress-bar|progress-bar|scrubber|seek-?bar|timeline/.test(cls)) {
                      if (n.getBoundingClientRect().width > 40) { bar = n; break; }
                    }
                  }
                  if (!bar) return;
                  var r = bar.getBoundingClientRect();
                  var frac = (e.clientX - r.left) / r.width;
                  if (frac < 0 || frac > 1) return;
                  var v = null, best = -1;
                  for (var i=0;i<vids.length;i++){ var d = vids[i].duration;
                    if (isFinite(d) && d > 0){ var vr = vids[i].getBoundingClientRect();
                      var a = vr.width*vr.height; if (a > best){ best = a; v = vids[i]; } } }
                  if (v){ v.currentTime = frac * v.duration; }
                } catch(_){}
              }
              document.addEventListener('pointerup', svSeekFromEvent, true);
              document.addEventListener('click', svSeekFromEvent, true);
              if (isInput(document.activeElement)) remember(document.activeElement);
              window.__svDefocus = function(){
                var el = window.__svActiveInput || document.activeElement;
                if(isInput(el)){ try{ el.blur(); }catch(_){} }
                window.__svActiveInput = null;
              };
              function caret(el){
                var s = (typeof el.selectionStart === 'number') ? el.selectionStart : (el.value||'').length;
                var e = (typeof el.selectionEnd === 'number') ? el.selectionEnd : s;
                return [s, e];
              }
              window.__svInsert = function(text){
                var el=activeInput(); if(!el) return;
                if(el.isContentEditable){
                  try{ el.focus({preventScroll:true}); }catch(_){}
                  if(!document.execCommand || !document.execCommand('insertText', false, text)){
                    el.textContent = (el.textContent||'') + text;
                  }
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));
                  return;
                }
                if(typeof el.value!=='string') return;
                var c=caret(el), s=c[0], e=c[1], v=el.value;
                var nv=v.slice(0,s)+text+v.slice(e);
                setNativeValue(el,nv);
                var pos=s+text.length;
                if(typeof el.selectionStart==='number') el.selectionStart=el.selectionEnd=pos;
                notify(el);
              };
              window.__svBackspace = function(){
                var el=activeInput(); if(!el) return;
                if(el.isContentEditable){
                  if(!document.execCommand || !document.execCommand('delete', false)){
                    el.textContent = (el.textContent||'').slice(0,-1);
                  }
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward'}));
                  return;
                }
                if(typeof el.value!=='string') return;
                var c=caret(el), s=c[0], e=c[1], v=el.value, nv, pos;
                if(s!==e){ nv=v.slice(0,s)+v.slice(e); pos=s; }
                else if(s>0){ nv=v.slice(0,s-1)+v.slice(s); pos=s-1; }
                else return;
                setNativeValue(el,nv);
                if(typeof el.selectionStart==='number') el.selectionStart=el.selectionEnd=pos;
                notify(el);
              };
              window.__svClear = function(){
                var el=activeInput(); if(!el) return;
                if(el.isContentEditable){ el.textContent=''; el.dispatchEvent(new InputEvent('input',{bubbles:true})); return; }
                if(typeof el.value!=='string') return;
                setNativeValue(el,''); notify(el);
              };
              window.__svMoveCaret = function(d){
                var el=activeInput(); if(!el || typeof el.selectionStart!=='number') return;
                var len=(el.value||'').length;
                var pos=Math.max(0, Math.min(len, el.selectionStart + d));
                el.selectionStart=el.selectionEnd=pos;
              };
              window.__svEnter = function(){
                var el=activeInput(); if(!el) return;
                ['keydown','keypress','keyup'].forEach(function(t){ el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true})); });
                if(el.form){ try{ el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit(); }catch(e){} }
              };
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun showKeyboard() {
        suppressImeFor(1200L)
        if (keyboardView == null) {
            keyboardView = CustomKeyboardView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
                )
                setOnKeyboardActionListener(this@MainActivity)
            }
            keyboardContainer.addView(keyboardView)
        }
        keyboardView?.visibility = View.VISIBLE
        keyboardContainer.visibility = View.VISIBLE
        keyboardContainer.bringToFront()
        keyboardView?.bringToFront()
        resetKeyboardHideTimer()
    }

    /** Auto-hide the on-screen keyboard after 10s of no key input. */
    private fun resetKeyboardHideTimer() {
        main.removeCallbacks(keyboardHideRunnable)
        main.postDelayed(keyboardHideRunnable, KEYBOARD_HIDE_MS)
    }

    private fun hideKeyboard() {
        main.removeCallbacks(keyboardHideRunnable)
        keyboardContainer.visibility = View.GONE
    }

    private fun handleLogicalClick(x: Float, y: Float): Boolean {
        if (dimMode) return true   // swallow clicks so a black screen can't pause the video
        suppressImeFor(1500L)
        if (keyboardContainer.visibility != View.VISIBLE) {
            webView.evaluateJavascript("window.__svDefocus && window.__svDefocus()", null)
            return false
        }
        val keyboard = keyboardView ?: return false
        val top = keyboardContainer.top.toFloat()
        val bottom = keyboardContainer.bottom.toFloat()
        if (y < top || y > bottom) {
            hideKeyboard()
            webView.evaluateJavascript("window.__svDefocus && window.__svDefocus()", null)
            return false
        }
        if (!keyboard.handleAnchoredTap(x, y - top)) return true
        suppressImeFor(900L)
        return true
    }

    private fun js(expr: String) = runCatching {
        suppressImeFor(900L)
        if (keyboardContainer.visibility == View.VISIBLE) resetKeyboardHideTimer()
        webView.requestFocus()
        webView.evaluateJavascript(expr, null)
    }

    override fun onKeyPressed(key: String) { js("window.__svInsert && window.__svInsert(${JSONObject.quote(key)})") }
    override fun onBackspacePressed() { js("window.__svBackspace && window.__svBackspace()") }
    override fun onEnterPressed() { js("window.__svEnter && window.__svEnter()") }
    override fun onHideKeyboard() = hideKeyboard()
    override fun onClearPressed() { js("window.__svClear && window.__svClear()") }
    override fun onMoveCursorLeft() { js("window.__svMoveCaret && window.__svMoveCaret(-1)") }
    override fun onMoveCursorRight() { js("window.__svMoveCaret && window.__svMoveCaret(1)") }

    /** Keyboard Mic key: Groq Whisper dictation into the focused field. */
    override fun onMicrophonePressed() {
        if (recorder.isRecording) stopVoiceAndProcess() else startVoice(VoiceMode.DICTATION)
    }

    // ------------------------------------------------------------------
    //  Window / lifecycle
    // ------------------------------------------------------------------

    private fun enableImmersiveFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
    }

    override fun onPause() {
        super.onPause()
        runCatching { CookieManager.getInstance().flush() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            // Dim first: it used to navigate the page while still blacked out,
            // so the user could not see what they had just done.
            dimMode -> exitDim()
            this::keyboardContainer.isInitialized && keyboardContainer.visibility == View.VISIBLE -> hideKeyboard()
            this::webView.isInitialized && webView.canGoBack() -> webView.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        GroqSpeech.stopSpeaking()
        runCatching { recorder.stop() }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        runCatching { CookieManager.getInstance().flush() }
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SmartView"
        private const val HOME = "https://duckduckgo.com/"

        /** Idle timeout for a running agent task. Generous on purpose: a real
         *  multi-step run goes quiet for a long time between LLM round trips,
         *  and this must only fire on a genuine stall. */
        private const val AGENT_IDLE_MS = 90_000L
        private const val INTERNAL_BASE = "https://smartview.internal/"
        private const val MAX_RECORD_MS = 8000L
        private const val AGENT_HIDE_MS = 5000L
        private const val KEYBOARD_HIDE_MS = 10000L
        // Current mobile-Chrome UA to satisfy Cloudflare (real engine is Chrome 95).
        private const val MODERN_UA =
            "Mozilla/5.0 (Linux; Android 12; RayNeo X3 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
