package com.micplugin

import android.app.Application
import com.micplugin.plugin.PluginPathPrefs
import com.micplugin.service.ShizukuManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MicPluginApp : Application() {

    @Inject lateinit var shizukuManager: ShizukuManager
    @Inject lateinit var pluginPathPrefs: PluginPathPrefs

    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)
        shizukuManager.init()
        pluginPathPrefs.init(this)
        listOf(
            "plugins/lv2", "plugins/clap", "plugins/vst3",
            "presets", "logs", "crashes"
        ).forEach { dir ->
            val f = java.io.File(filesDir, dir)
            if (!f.exists()) f.mkdirs()
        }
        installBundledPlugin()
    }

    /**
     * Null Commander FX is built by CI and packed into the APK under assets/plugins/<abi>/.
     * Copy the one that matches this phone into the plugin folder, refreshing it whenever a
     * newer app version is installed. Skipped silently when the APK has no bundled plugin.
     */
    private fun installBundledPlugin() {
        try {
            val prefs = getSharedPreferences("micup_prefs", MODE_PRIVATE)
            val dest = java.io.File(filesDir, "plugins/clap/NullCommander.clap")
            val versionNow = BuildConfig.VERSION_CODE
            if (dest.exists() && prefs.getInt("bundled_plugin_version", 0) == versionNow) return
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { a ->
                assets.list("plugins/$a")?.contains("NullCommander.clap") == true
            } ?: return
            assets.open("plugins/$abi/NullCommander.clap").use { input ->
                val tmp = java.io.File(dest.parentFile, "NullCommander.clap.tmp")
                tmp.outputStream().use { input.copyTo(it) }
                tmp.renameTo(dest)
            }
            prefs.edit().putInt("bundled_plugin_version", versionNow).apply()
            android.util.Log.i("MicUp.App", "Installed bundled Null Commander ($abi)")
        } catch (e: Throwable) {
            android.util.Log.e("MicUp.App", "Bundled plugin install failed: $e")
        }
    }
}
