package com.smartview.app

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Network-level ad and tracker blocking for the WebView.
 *
 * On a waveguide display a 250px ad slot eats a large fraction of the readable
 * area, and until now the app did no filtering at all.
 *
 * Source list: StevenBlack unified hosts (MIT) — the only widely-used list
 * whose licence is unambiguously fine to redistribute inside an APK. Bundled as
 * a gzipped asset so there is no startup network fetch. Refresh with
 * tools/fetch_blocklist.sh.
 *
 * The lookup runs on EVERY subresource of every page, on the network thread, so
 * it must be allocation-free and O(labels). A HashSet of ~93k interned strings
 * costs a few MB and answers in constant time per parent-domain walk — a regex
 * or list scan at this call rate would be visible as page-load jank.
 */
object AdBlock {

    private const val TAG = "SmartView"
    private const val ASSET = "blocklist.txt.gz"

    /** null = not loaded. Deliberately NOT an empty set: an empty set would
     *  answer "allow" forever while looking perfectly healthy. */
    @Volatile private var blocked: HashSet<String>? = null
    @Volatile var loadError: String? = null
        private set

    @Volatile var enabled: Boolean = true

    /** Hosts that must never be blocked even if a list disagrees: the search
     *  engine the app is built around, and Cloudflare's challenge endpoint —
     *  blocking that would make protected sites unreachable with no way for the
     *  user to diagnose it on-device. */
    private val ALLOW = arrayOf(
        "duckduckgo.com",
        "challenges.cloudflare.com",
        "smartview.internal"
    )

    fun warmUp(context: Context) {
        if (blocked != null) return
        Thread {
            val t0 = System.currentTimeMillis()
            runCatching {
                val set = HashSet<String>(120_000)
                // AGP transparently GUNZIPS .gz assets at packaging time, so the
                // file that actually ships is "blocklist.txt" even though the
                // repo holds "blocklist.txt.gz". Accept whichever is present
                // rather than depending on that behaviour staying put.
                val (stream, gzipped) = runCatching {
                    context.assets.open(ASSET) to true
                }.getOrElse {
                    context.assets.open(ASSET.removeSuffix(".gz")) to false
                }
                stream.use { raw ->
                    val reader =
                        if (gzipped) GZIPInputStream(raw).bufferedReader()
                        else raw.bufferedReader()
                    reader.forEachLine { line ->
                        val h = line.trim()
                        if (h.isNotEmpty() && h[0] != '#') set.add(h)
                    }
                }
                if (set.size < 10_000) {
                    loadError = "blocklist too small (${set.size})"
                    Log.w(TAG, "AdBlock: ${loadError}")
                } else {
                    blocked = set
                    loadError = null
                    Log.d(TAG, "AdBlock: ${set.size} domains in ${System.currentTimeMillis() - t0}ms")
                }
            }.onFailure {
                loadError = it.message ?: "asset missing"
                Log.w(TAG, "AdBlock: failed to load $ASSET — ${it.message}")
            }
        }.apply { isDaemon = true; name = "AdBlockLoad" }.start()
    }

    fun ready(): Boolean = blocked != null
    fun size(): Int = blocked?.size ?: 0

    /** Per-page tally, so an over-blocked site can be diagnosed in the field —
     *  there are no dev tools on the glasses. */
    @Volatile private var pageBlocks = 0
    fun resetCount() { pageBlocks = 0 }
    fun blockCount(): Int = pageBlocks

    /** Walk parent labels: a.b.tracker.com is blocked by an entry for tracker.com. */
    fun isBlocked(host: String?): Boolean {
        if (!enabled || host.isNullOrEmpty()) return false
        val set = blocked ?: return false
        val h = host.lowercase().removePrefix("www.")
        for (a in ALLOW) if (h == a || h.endsWith(".$a")) return false
        if (set.contains(h)) return true
        var i = h.indexOf('.')
        while (i in 0 until h.length - 1) {
            val parent = h.substring(i + 1)
            if (parent.indexOf('.') < 0) return false   // stop at the TLD
            if (set.contains(parent)) return true
            i = h.indexOf('.', i + 1)
        }
        return false
    }

    private val GIF_1PX = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00,
        0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44,
        0x01, 0x00, 0x3B
    )

    /**
     * A stub that keeps page JS happy.
     *
     * The naive "empty 200, no headers" response is actively harmful: a blocked
     * cross-origin XHR then fails CORS and throws INSIDE page code, which is a
     * louder breakage than the ad itself — and page-agent cannot recover from a
     * page whose scripts died. So: always send an ACAO header, and match the
     * body to what the caller asked for.
     */
    fun stub(request: WebResourceRequest?): WebResourceResponse {
        val accept = request?.requestHeaders?.get("Accept").orEmpty().lowercase()
        val path = request?.url?.path.orEmpty().lowercase()
        val headers = HashMap<String, String>(4).apply {
            put("Access-Control-Allow-Origin", "*")
            put("Access-Control-Allow-Headers", "*")
            put("Cache-Control", "no-store")
        }
        return when {
            accept.contains("json") ->
                WebResourceResponse("application/json", "utf-8", 200, "OK", headers,
                    ByteArrayInputStream("{}".toByteArray()))
            accept.contains("image") || path.endsWith(".gif") || path.endsWith(".png") ||
                path.endsWith(".jpg") || path.endsWith(".webp") ->
                WebResourceResponse("image/gif", null, 200, "OK", headers,
                    ByteArrayInputStream(GIF_1PX))
            else ->
                WebResourceResponse("application/javascript", "utf-8", 200, "OK", headers,
                    ByteArrayInputStream(ByteArray(0)))
        }
    }

    /** @return a stub response to block, or null to let the request proceed. */
    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (!isBlocked(url.host)) return null
        pageBlocks++
        return stub(request)
    }
}
