package io.nekohasekai.sagernet.utils

import android.app.Activity
import android.view.WindowManager
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.GroupType

/**
 * Amigos anti-sharing / hardening helpers.
 *
 * Premium = profiles imported from the per-user Amigos subscription
 * (https://amigos.r4in8ow.online/sub/{username}). Sharing one username's
 * subscription would leak the whole account, so every export path
 * (share sheet, copy link, QR code, config-file export) is disabled for
 * premium groups and premium profiles. Free-tier profiles keep basic share.
 */
object AmigosSecurity {

    /** True when [group] is an Amigos premium subscription group. */
    fun isPremiumGroup(group: ProxyGroup?): Boolean {
        return group != null &&
            group.type == GroupType.SUBSCRIPTION &&
            group.subscription?.link?.startsWith(Key.AMIGOS_SUB_BASE) == true
    }

    /** DB-backed variant for callers that only have a group id. */
    suspend fun isPremiumGroupId(groupId: Long): Boolean {
        return isPremiumGroup(SagerDatabase.groupDao.getById(groupId))
    }

    /**
     * Blocks screenshots and screen recording for the given activity.
     * Applied to server-list, profile, detail and auth screens.
     */
    fun applyFlagSecure(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
