package board.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import board.core.Ids
import board.core.NetPlatform

object Platform {
    fun isTv(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun deviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("board", Context.MODE_PRIVATE)
        return prefs.getString("deviceId", null) ?: Ids.random(10).also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("board", Context.MODE_PRIVATE)

    /** Планшет вручную переведён в режим ТВ. */
    fun tvModePref(ctx: Context) = prefs(ctx).getBoolean("tvMode", false)
    fun setTvModePref(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("tvMode", on).apply()

    fun deviceName(ctx: Context): String {
        val name = if (Build.VERSION.SDK_INT >= 25) {
            runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        } else null
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"
    }
}

/** Без multicast-блокировки многие устройства отбрасывают входящие broadcast-пакеты. */
class AndroidNetPlatform(ctx: Context) : NetPlatform {
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val lock = wifi?.createMulticastLock("onlineboard")?.apply { setReferenceCounted(false) }

    override fun acquire() {
        runCatching { lock?.acquire() }
    }

    override fun release() {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }
}
