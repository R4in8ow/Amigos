package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

object AmigosAccount {

    data class UserInfo(
        val username: String,
        val expiry: Long,
        val up: Long,
        val down: Long,
        val total: Long,
    )

    class NotFoundException : Exception()

    suspend fun fetch(username: String): UserInfo = suspendCancellableCoroutine { cont ->
        runOnDefaultDispatcher {
            try {
                val url = Key.AMIGOS_API_BASE + "user/" + URLEncoder.encode(username, "UTF-8")
                val client = Libcore.newHttpClient().apply { modernTLS() }
                val response = client.newRequest().apply { setURL(url) }.execute()
                val json = JSONObject(Util.getStringBox(response.contentString))
                if (!json.has("username")) {
                    if (json.optString("detail").contains("not found", ignoreCase = true)) {
                        throw NotFoundException()
                    }
                    throw IllegalStateException("unexpected response")
                }
                cont.resume(
                    UserInfo(
                        username = json.getString("username"),
                        expiry = json.optLong("expiry", 0L),
                        up = json.optLong("up", 0L),
                        down = json.optLong("down", 0L),
                        total = json.optLong("total", 0L),
                    )
                )
            } catch (e: Exception) {
                Logs.w("Amigos account fetch failed: ${e.javaClass.simpleName}")
                cont.resumeWith(Result.failure(e))
            }
        }
    }

    fun isExpired(info: UserInfo, now: Long = System.currentTimeMillis()): Boolean =
        info.expiry > 0 && info.expiry < now

    fun expiryLabel(info: UserInfo): String =
        if (info.expiry <= 0) {
            "Lifetime"
        } else {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(info.expiry))
        }

    fun usageLabel(info: UserInfo): String {
        val used = info.up + info.down
        return if (info.total <= 0) {
            "${formatBytes(used)} used / Unlimited"
        } else {
            "${formatBytes(used)} / ${formatBytes(info.total)}"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
