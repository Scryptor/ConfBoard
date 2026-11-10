package board.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import board.core.FileBoardStore
import board.core.Member
import board.core.Session
import board.core.UdpBeaconTransport
import java.io.File
import java.util.concurrent.Executor

class MainActivity : ComponentActivity() {
    private lateinit var session: Session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Для отладки: adb shell am start -n board.online/board.app.MainActivity --es mode tablet|tv
        val forced = intent?.getStringExtra("mode")
        val realTv = when (forced) {
            "tablet" -> false
            "tv" -> true
            else -> Platform.isTv(this)
        }
        val startAsTv = !realTv && Platform.tvModePref(this)
        val isTv = realTv || startAsTv
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setFullscreen(isTv)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        board.core.CoreLog.sink = { android.util.Log.i("OnlineBoard", it) }
        val main = Handler(Looper.getMainLooper())
        session = Session(
            me = Member(Platform.deviceId(this), Platform.deviceName(this), isTv),
            store = FileBoardStore(File(filesDir, "boards")),
            exec = Executor { main.post(it) },
            beacons = UdpBeaconTransport(platform = AndroidNetPlatform(this)),
        )
        val controller = BoardController(session, realTv, startAsTv, resources.displayMetrics.density) { on ->
            Platform.setTvModePref(this, on)
            setFullscreen(on)
        }
        session.listener = controller
        session.start()

        setContent { App(controller) }
    }

    private fun setFullscreen(on: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (on) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun onDestroy() {
        session.stop()
        super.onDestroy()
    }
}
