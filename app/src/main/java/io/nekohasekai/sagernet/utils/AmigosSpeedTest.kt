package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Amigos speed test: measures real throughput through the active VPN tunnel
 * (traffic is routed system-wide, so plain OkHttp requests go through it)
 * and resolves the exit IP country via Cloudflare trace.
 */
object AmigosSpeedTest {

    data class ExitInfo(val ip: String, val countryCode: String)
    data class TestResult(val downMbps: Double, val upMbps: Double)

    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private const val DOWNLOAD_BYTES = 20_000_000L
    private const val UPLOAD_BYTES = 8_000_000L
    private const val MAX_TEST_SECONDS = 30L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchExitInfo(): ExitInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(TRACE_URL).header("User-Agent", "Amigos").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                var ip = ""
                var loc = ""
                for (line in body.lineSequence()) {
                    when {
                        line.startsWith("ip=") -> ip = line.substringAfter("=").trim()
                        line.startsWith("loc=") -> loc = line.substringAfter("=").trim()
                    }
                }
                if (loc.length != 2) null else ExitInfo(ip, loc.uppercase())
            }
        } catch (e: Exception) {
            Logs.w("AmigosSpeedTest: exit info failed: ${e.message}")
            null
        }
    }

    suspend fun runDownloadTest(onProgress: (Float) -> Unit): Double = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$DOWNLOAD_URL?bytes=$DOWNLOAD_BYTES").header("User-Agent", "Amigos").build()
        var total = 0L
        val start = System.nanoTime()
        val deadline = start + MAX_TEST_SECONDS * 1_000_000_000L
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val stream = resp.body?.byteStream() ?: throw IOException("empty body")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                onProgress(total.toFloat() / DOWNLOAD_BYTES)
                if (System.nanoTime() > deadline) break
            }
        }
        val seconds = (System.nanoTime() - start).toDouble() / 1_000_000_000.0
        if (seconds <= 0.0 || total == 0L) throw IOException("no data received")
        total * 8.0 / 1_000_000.0 / seconds
    }

    suspend fun runUploadTest(onProgress: (Float) -> Unit): Double = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        val req = Request.Builder()
            .url(UPLOAD_URL)
            .header("User-Agent", "Amigos")
            .post(CountingRequestBody(UPLOAD_BYTES, onProgress))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.close()
        }
        val seconds = (System.nanoTime() - start).toDouble() / 1_000_000_000.0
        if (seconds <= 0.0) throw IOException("upload failed")
        UPLOAD_BYTES * 8.0 / 1_000_000.0 / seconds
    }

    private class CountingRequestBody(
        private val size: Long,
        private val onProgress: (Float) -> Unit,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun writeTo(sink: BufferedSink) {
            val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
            var written = 0L
            while (written < size) {
                val n = min(chunk.size.toLong(), size - written).toInt()
                sink.write(chunk, 0, n)
                written += n
                onProgress(written.toFloat() / size)
            }
        }
    }

    fun countryFlag(code: String): String {
        val upper = code.uppercase()
        if (upper.length != 2 || !upper.all { it in 'A'..'Z' }) return ""
        return upper.map { c -> String(Character.toChars(0x1F1E6 + (c - 'A'))) }.joinToString("")
    }

    fun countryName(code: String): String = COUNTRY_NAMES[code.uppercase()] ?: code.uppercase()

    /** V2Box-style masked IP: 206.****.196 / 2400****5000 */
    fun maskIp(ip: String): String {
        if (ip.isEmpty()) return ""
        return if (ip.contains(".")) {
            val parts = ip.split(".")
            if (parts.size == 4) "${parts[0]}.****.${parts[3]}" else ip
        } else {
            val clean = ip.replace(":", "")
            if (clean.length >= 8) "${clean.take(4)}****${clean.takeLast(4)}" else ip
        }
    }

    private val COUNTRY_NAMES = mapOf(
        "US" to "United States", "SG" to "Singapore", "TH" to "Thailand",
        "CA" to "Canada", "JP" to "Japan", "KR" to "South Korea",
        "GB" to "United Kingdom", "DE" to "Germany", "FR" to "France",
        "NL" to "Netherlands", "AU" to "Australia", "IN" to "India",
        "MM" to "Myanmar", "MY" to "Malaysia", "VN" to "Vietnam",
        "ID" to "Indonesia", "PH" to "Philippines", "TW" to "Taiwan",
        "HK" to "Hong Kong", "CN" to "China", "RU" to "Russia",
        "UA" to "Ukraine", "TR" to "Turkey", "AE" to "UAE",
        "SA" to "Saudi Arabia", "BR" to "Brazil", "MX" to "Mexico",
        "AR" to "Argentina", "ZA" to "South Africa", "EG" to "Egypt",
        "NG" to "Nigeria", "KE" to "Kenya", "IT" to "Italy",
        "ES" to "Spain", "SE" to "Sweden", "NO" to "Norway",
        "FI" to "Finland", "DK" to "Denmark", "PL" to "Poland",
        "CH" to "Switzerland", "AT" to "Austria", "BE" to "Belgium",
        "IE" to "Ireland", "PT" to "Portugal", "GR" to "Greece",
        "IL" to "Israel", "KZ" to "Kazakhstan", "UZ" to "Uzbekistan",
        "BD" to "Bangladesh", "PK" to "Pakistan", "LK" to "Sri Lanka",
        "NP" to "Nepal", "KH" to "Cambodia", "LA" to "Laos",
        "NZ" to "New Zealand",
    )
}
