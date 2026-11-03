package app.xingchuan.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.xingchuan.tv.core.Apps
import app.xingchuan.tv.core.Icons
import app.xingchuan.tv.core.LauncherModel
import app.xingchuan.tv.ui.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var model: LauncherModel

    /** 安装/卸载/更新后重扫应用列表，桌面不会停留在旧数据上。 */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.data?.scheme != "package") return
            // 同一个包升级后图标可能换掉，缓存必须先失效
            Icons.clear()
            reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // 桌面常驻，避免按键时状态栏被拉起
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )

        model = LauncherModel(applicationContext)
        setContent { HomeScreen(model) }
        reload()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 再次按 Home 时收起所有覆盖层
        model.drawerOpen = false
        model.settingsOpen = false
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching {
            ContextCompat.registerReceiver(
                this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(packageReceiver) }
        super.onStop()
    }

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { Apps.load(this@MainActivity) }
            model.applyLoaded(list)
        }
    }
}
