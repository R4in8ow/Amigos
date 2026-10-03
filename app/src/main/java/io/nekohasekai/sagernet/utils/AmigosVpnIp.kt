package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

object AmigosVpnIp {

    data class Location(val ip: String, val country: String?, val countryCode: String?)

    suspend fun fetch(): Location? = suspendCancellableCoroutine { cont ->
        runOnDefaultDispatcher {
            val result = tryLocation("https://ipapi.co/json/")
                ?: tryLocation("http://ip-api.com/json/?fields=status,country,countryCode,query")
                ?: tryLocation("https://api.ipify.org?format=json")
            if (result == null) {
                Logs.w("Amigos VPN IP check failed on all endpoints")
            }
            cont.resume(result)
        }
    }

    private fun tryLocation(url: String): Location? {
        return try {
            val client = Libcore.newHttpClient().apply { modernTLS() }
            val response = client.newRequest().apply { setURL(url) }.execute()
            val json = JSONObject(Util.getStringBox(response.contentString))
            if (json.optString("status", "success") == "fail") return null
            val ip = when {
                json.has("ip") -> json.optString("ip", "")
                json.has("query") -> json.optString("query", "")
                else -> ""
            }.trim()
            if (ip.isEmpty()) return null
            val country = json.optString("country_name", "").ifBlank {
                json.optString("country", "")
            }.ifBlank { null }
            val countryCode = json.optString("country_code", "").ifBlank {
                json.optString("countryCode", "")
            }.ifBlank { null }
            Location(ip, country, countryCode)
        } catch (e: Exception) {
            Logs.w("Amigos VPN IP check failed for $url: ${e.readableMessage}")
            null
        }
    }

    fun maskIp(ip: String): String {
        val v4 = ip.split(".")
        if (v4.size == 4) return "${v4[0]}.***.***.${v4[3]}"
        val v6 = ip.split(":")
        if (v6.size > 2) return "${v6[0]}:***:${v6.last()}"
        return ip
    }

    fun maskHost(host: String): String {
        if (host.length <= 8) return host
        return host.take(4) + "****" + host.takeLast(4)
    }

    fun countryFlag(countryCode: String?): String {
        val cc = countryCode?.uppercase(Locale.US) ?: return ""
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return ""
        return cc.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }
}
