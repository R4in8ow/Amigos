package io.nekohasekai.sagernet.free

import com.r4in8ow.amigos.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Amigos free-tier server list.
 *
 * The list is NOT baked into the app (no hardcoded third-party configs).
 * It is fetched over HTTPS from [BuildConfig.FREE_SERVERS_URL]
 * (default https://amigos.r4in8ow.online/free-servers.json — change the
 * FREE_SERVERS_URL build constant in app/build.gradle.kts to point elsewhere).
 *
 * Expected JSON schema (see docs/free-servers.example.json):
 *   {
 *     "updated_at": "2026-10-02T00:00:00Z",
 *     "servers": [
 *       {"region":"Singapore","country_code":"SG","name":"SG Free #1",
 *        "type":"vless","link":"vless://..."}
 *     ]
 *   }
 * Supported link types: vless, vmess, trojan, ss. Unknown types are skipped.
 *
 * SECURITY NOTE: free servers are operated by third parties, not by Amigos.
 * Treat their traffic as untrusted — the app shows a disclaimer dialog on
 * first use. Links/credentials from the list are never written to logcat.
 */
object FreeServerManager {

    data class FreeServerEntry(
        val region: String,
        val countryCode: String,
        val name: String,
        val type: String,
        val link: String
    )

    data class FetchResult(
        val updatedAt: String,
        val servers: List<FreeServerEntry>
    )

    val SUPPORTED_TYPES = setOf("vless", "vmess", "trojan", "ss")

    const val GROUP_NAME = "Free Servers"

    private const val CACHE_FILE = "free_servers.json"

    private fun cacheFile(): File = File(app.filesDir, CACHE_FILE)

    /**
     * Fetches the remote JSON over HTTPS and validates the schema.
     * @throws Exception on network failure, non-HTTPS URL, or schema violation.
     * Never logs links or credentials.
     */
    suspend fun fetchRemote(): FetchResult {
        val url = BuildConfig.FREE_SERVERS_URL
        val scheme = url.substringBefore("://").lowercase()
        if (scheme != "https") {
            throw IllegalArgumentException("Free-server URL must use HTTPS")
        }
        val text = try {
            val client = Libcore.newHttpClient().apply { modernTLS() }
            val response = client.newRequest().apply { setURL(url) }.execute()
            Util.getStringBox(response.contentString)
        } catch (e: Exception) {
            throw IllegalStateException("fetch failed: ${e.message}")
        }
        return parseAndValidate(text)
    }

    /** Parses + validates raw JSON text against the free-servers schema. */
    fun parseAndValidate(text: String): FetchResult {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid JSON")
        }
        val updatedAt = root.optString("updated_at", "")
        val arr: JSONArray = root.optJSONArray("servers")
            ?: throw IllegalArgumentException("missing 'servers' array")
        val out = ArrayList<FreeServerEntry>(arr.length())
        var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { skipped++; continue }
            val region = o.optString("region", "").trim()
            val name = o.optString("name", "").trim()
            val type = o.optString("type", "").trim().lowercase()
            val link = o.optString("link", "").trim()
            val countryCode = o.optString("country_code", "").trim().uppercase()
            if (region.isEmpty() || name.isEmpty() || link.isEmpty()) {
                skipped++
                continue
            }
            if (type !in SUPPORTED_TYPES || !link.startsWith("$type://")) {
                // Unknown/unsupported server type — ignore gracefully.
                skipped++
                continue
            }
            out.add(FreeServerEntry(region, countryCode, name, type, link))
        }
        if (skipped > 0) Logs.d("FreeServers: skipped $skipped unsupported entries")
        Logs.d("FreeServers: validated ${out.size} servers (updated_at=$updatedAt)")
        return FetchResult(updatedAt, out)
    }

    /** Returns the locally cached list, or null when nothing was cached yet. */
    fun loadCached(): FetchResult? {
        val f = cacheFile()
        if (!f.exists()) return null
        return try {
            parseAndValidate(f.readText())
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
    }

    private fun saveCache(text: String, updatedAt: String) {
        try {
            cacheFile().writeText(text)
            DataStore.freeServersUpdatedAt = updatedAt
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    /**
     * Full refresh: fetch remote JSON, validate, cache, and sync profiles
     * into the "Free Servers" group. Returns the new list.
     * @throws Exception on fetch/validation failure (cached list is kept).
     */
    suspend fun refresh(): FetchResult {
        val url = BuildConfig.FREE_SERVERS_URL
        if (url.substringBefore("://").lowercase() != "https") {
            throw IllegalArgumentException("Free-server URL must use HTTPS")
        }
        val text: String
        try {
            val client = Libcore.newHttpClient().apply { modernTLS() }
            val response = client.newRequest().apply { setURL(url) }.execute()
            text = Util.getStringBox(response.contentString)
        } catch (e: Exception) {
            throw IllegalStateException("fetch failed: ${e.message}")
        }
        val result = parseAndValidate(text)
        saveCache(text, result.updatedAt)
        syncToGroup(result.servers)
        return result
    }

    /** Finds the free-servers group, creating it on first use. */
    suspend fun findOrCreateGroup(): ProxyGroup {
        val existing = SagerDatabase.groupDao.allGroups()
            .firstOrNull { it.type == GroupType.BASIC && it.name == GROUP_NAME }
        if (existing != null) return existing
        return GroupManager.createGroup(
            GroupManager.createGroup(ProxyGroup(name = GROUP_NAME, type = GroupType.BASIC))
        )
    }

    /**
     * Replaces the profiles in the "Free Servers" group with [servers].
     * Each link is parsed with the type-specific parser directly (bypassing
     * the chatty generic dispatcher) so credentials never hit logcat.
     */
    suspend fun syncToGroup(servers: List<FreeServerEntry>) {
        val group = findOrCreateGroup()
        SagerDatabase.proxyDao.deleteByGroup(group.id)
        var imported = 0
        for (entry in servers) {
            val bean: AbstractBean? = try {
                when (entry.type) {
                    "vless", "vmess" -> parseV2Ray(entry.link)
                    "trojan" -> parseTrojan(entry.link)
                    "ss" -> parseShadowsocks(entry.link)
                    else -> null
                }
            } catch (e: Exception) {
                Logs.w("FreeServers: failed to parse '${entry.name}' (${entry.region}): ${e.message}")
                null
            }
            if (bean == null) continue
            // Use the curated display name from the JSON, not the link remark.
            bean.name = entry.name
            try {
                ProfileManager.createProfile(group.id, bean)
                imported++
            } catch (e: Exception) {
                Logs.w("FreeServers: failed to import '${entry.name}': ${e.message}")
            }
        }
        GroupManager.postUpdate(group)
        Logs.d("FreeServers: imported $imported/${servers.size} into group ${group.id}")
    }

    /** Last-updated label for the UI ("" when never fetched). */
    fun lastUpdatedLabel(): String = DataStore.freeServersUpdatedAt
}
