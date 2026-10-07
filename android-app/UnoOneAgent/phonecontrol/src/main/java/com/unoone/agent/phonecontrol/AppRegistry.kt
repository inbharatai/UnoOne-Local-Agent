package com.unoone.agent.phonecontrol

import android.content.Context
import android.content.Intent
import java.util.Locale

/** Visible launchable apps only. An empty result means unavailable/hidden, never permission to guess. */
class AppRegistry(context: Context, private val aliases: Map<String, Set<String>> = emptyMap()) {
    private val pm = context.applicationContext.packageManager
    data class App(val packageName: String, val label: String, val versionCode: Long)
    sealed class Resolution {
        data class Found(val app: App) : Resolution()
        data class Ambiguous(val packages: Set<String>) : Resolution()
        data object Unavailable : Resolution()
    }
    @Suppress("DEPRECATION")
    fun discover(): List<App> = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .mapNotNull { result ->
            val info = result.activityInfo ?: return@mapNotNull null
            if (!info.enabled || !info.applicationInfo.enabled || !info.exported) return@mapNotNull null
            runCatching { App(info.packageName, result.loadLabel(pm).toString(), pm.getPackageInfo(info.packageName, 0).longVersionCode) }.getOrNull()
        }.distinctBy { it.packageName }.sortedBy { it.packageName }
    /** Resolves at use-time, so install/removal and label changes cannot leave a stale authority cache. */
    fun resolve(name: String): Resolution {
        val key = name.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return Resolution.Unavailable
        val visible = discover()
        val aliasPackages = aliases.entries.filter { it.key.lowercase(Locale.ROOT) == key }.flatMap { it.value }.toSet()
        val matches = visible.filter { it.packageName.lowercase(Locale.ROOT) == key || it.label.lowercase(Locale.ROOT) == key || it.packageName in aliasPackages }
        return when (matches.size) {
            0 -> Resolution.Unavailable
            1 -> Resolution.Found(matches.single())
            else -> Resolution.Ambiguous(matches.map { it.packageName }.toSet())
        }
    }
    /** Compatibility is opt-in and still checked against current visibility; legacy resolver is unchanged. */
    fun resolveLegacyName(name: String): Resolution {
        val direct = resolve(name)
        if (direct !is Resolution.Unavailable) return direct
        return PackageResolver.resolveAppName(name)?.let { resolve(it) } ?: Resolution.Unavailable
    }
    fun launchIntent(name: String): Intent? = (resolve(name) as? Resolution.Found)?.let {
        pm.getLaunchIntentForPackage(it.app.packageName)?.setPackage(it.app.packageName)
    }
}
