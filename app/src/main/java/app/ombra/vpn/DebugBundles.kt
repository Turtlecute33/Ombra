package app.ombra.vpn

import app.ombra.core.model.BundleInfo
import app.ombra.core.model.CoreCommands
import java.io.File

/**
 * Debug bundles. Rust writes the zip into `<cacheDir>/debug-archives`, which the FileProvider
 * (authority `${applicationId}.files`, res/xml/file_paths.xml) shares. A bundle holds logs and
 * network probe results, so only the newest one is kept.
 */
class DebugBundles(
    private val cacheDir: File,
    private val bundleInfo: BundleInfo,
    private val core: CoreChannel,
) {
    /** Returns the absolute path of the new zip. Call only after the user agreed on the consent screen. */
    suspend fun create(feedback: String?): String {
        File(cacheDir, DIR).listFiles()?.forEach { it.delete() }
        return core.call(CoreCommands.createDebugBundle(feedback, bundleInfo, cacheDir.absolutePath))
    }

    companion object {
        /** Fixed by Rust (debug_bundle/builder.rs). */
        const val DIR = "debug-archives"
    }
}
