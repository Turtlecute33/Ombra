package app.ombra.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

/** An app that the split tunnel can include or exclude. */
@Immutable
data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean)

/**
 * Apps with a launcher icon, read from the PackageManager off the main thread. The manifest
 * declares a `<queries>` entry for launcher apps, so Ombra needs no QUERY_ALL_PACKAGES.
 */
class InstalledApps(context: Context) {
    private val packageManager = context.packageManager
    private val ownPackage = context.packageName
    private val icons = LruCache<String, ImageBitmap>(ICON_CACHE_SIZE)

    /** Sorted by label; without Ombra, which is always outside the tunnel. */
    suspend fun launchable(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance()
        packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ownPackage }
            .map { info -> InstalledApp(info.packageName, info.loadLabel(packageManager).toString(), info.isPreinstalled()) }
            .sortedWith(compareBy(collator) { it.label })
    }

    /** The icon at [sizePx], or null when the app is gone. Recent icons stay in memory while the list scrolls. */
    suspend fun icon(packageName: String, sizePx: Int): ImageBitmap? {
        val key = "$packageName@$sizePx"
        icons.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap().also { icons.put(key, it) }
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    /** Part of the system image and never updated by the user: Settings, Camera and similar. */
    private fun ApplicationInfo.isPreinstalled(): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

    private companion object {
        const val ICON_CACHE_SIZE = 120
    }
}
