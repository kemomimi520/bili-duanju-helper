package com.bili.drama.accessibility

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bili.drama.core.BiliNodes

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var seekSwitch: Switch
    private lateinit var interceptJumpSwitch: Switch
    private lateinit var overlaySwitch: Switch
    private lateinit var onlyInBiliSwitch: Switch
    private lateinit var externalStayInput: EditText
    private lateinit var postSeekWaitInput: EditText
    private lateinit var adTimeoutInput: EditText

    private var syncingOverlaySwitch = false

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatusAndLogs()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (36 * dp).toInt(), pad, pad)
        }
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "B站短剧自动看广告插件（无障碍版）"
            textSize = 20f
        }
        root.addView(title)

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }
        root.addView(statusView)

        val btnOpenAcc = Button(this).apply {
            text = "1. 前往开启无障碍权限"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(btnOpenAcc)

        val btnOpenAppInfo = Button(this).apply {
            text = "（若开关置灰）打开本应用信息 -> 允许受限制设置"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
        root.addView(btnOpenAppInfo)

        val btnLaunchBili = Button(this).apply {
            text = "2. 打开哔哩哔哩（进入短剧后点悬浮胶囊开始）"
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage(BiliNodes.PKG_BILI)
                if (launch != null) {
                    AccPrefs.setOverlayEnabled(this@MainActivity, true)
                    syncingOverlaySwitch = true
                    overlaySwitch.isChecked = true
                    syncingOverlaySwitch = false
                    DramaAccessibilityService.instance?.refreshOverlayVisibility()
                    startActivity(launch)
                } else {
                    Toast.makeText(this@MainActivity, "未找到哔哩哔哩", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(btnLaunchBili)

        val btnShutdownAll = Button(this).apply {
            text = "关闭悬浮窗并退出无障碍服务"
            setOnClickListener {
                val svc = DramaAccessibilityService.instance
                if (svc != null) {
                    svc.shutdownSelf()
                    Toast.makeText(this@MainActivity, "已关闭悬浮窗并退出服务", Toast.LENGTH_SHORT).show()
                } else {
                    AccPrefs.setOverlayEnabled(this@MainActivity, false)
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                refreshStatusAndLogs()
            }
        }
        root.addView(btnShutdownAll)

        val cfg = AccPrefs.loadConfig(this)

        seekSwitch = Switch(this).apply {
            text = "快进进度条检测广告（切集后直接拖到中段/尾段）"
            isChecked = cfg.seekCheckEnabled
            setPadding(0, (10 * dp).toInt(), 0, (6 * dp).toInt())
        }
        root.addView(seekSwitch)

        interceptJumpSwitch = Switch(this).apply {
            text = "拦截自动跳出/商店下载并自动拉回继续广告计时"
            isChecked = cfg.interceptExternalJumps
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        root.addView(interceptJumpSwitch)

        overlaySwitch = Switch(this).apply {
            text = "启用无障碍悬浮控制胶囊"
            isChecked = AccPrefs.isOverlayEnabled(this@MainActivity)
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { _, isChecked ->
                if (syncingOverlaySwitch) return@setOnCheckedChangeListener
                AccPrefs.setOverlayEnabled(this@MainActivity, isChecked)
                DramaAccessibilityService.instance?.refreshOverlayVisibility()
            }
        }
        root.addView(overlaySwitch)

        onlyInBiliSwitch = Switch(this).apply {
            text = "仅在哔哩哔哩内显示悬浮窗（离开B站自动隐藏）"
            isChecked = AccPrefs.isOnlyShowInBili(this@MainActivity)
            setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt())
            setOnCheckedChangeListener { _, isChecked ->
                AccPrefs.setOnlyShowInBili(this@MainActivity, isChecked)
                DramaAccessibilityService.instance?.refreshOverlayVisibility()
            }
        }
        root.addView(onlyInBiliSwitch)

        root.addView(TextView(this).apply { text = "跳转外部软件体验停留秒数（默认 11s）：" })
        externalStayInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(cfg.externalStaySeconds.toString())
        }
        root.addView(externalStayInput)

        root.addView(TextView(this).apply { text = "快进进度条后等待锁弹出毫秒数（默认 900ms）：" })
        postSeekWaitInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(cfg.postSeekWaitMs.toString())
        }
        root.addView(postSeekWaitInput)

        root.addView(TextView(this).apply { text = "单个广告最大等待秒数（默认 45s）：" })
        adTimeoutInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(cfg.adTimeoutSeconds.toString())
        }
        root.addView(adTimeoutInput)

        val btnSave = Button(this).apply {
            text = "保存参数并应用"
            setOnClickListener {
                val stay = externalStayInput.text.toString().toIntOrNull() ?: 11
                val seekWait = postSeekWaitInput.text.toString().toLongOrNull() ?: 900L
                val timeout = adTimeoutInput.text.toString().toIntOrNull() ?: 45
                AccPrefs.saveConfig(
                    this@MainActivity,
                    seekSwitch.isChecked,
                    interceptJumpSwitch.isChecked,
                    stay,
                    timeout,
                    seekWait
                )
                AccPrefs.setOverlayEnabled(this@MainActivity, overlaySwitch.isChecked)
                AccPrefs.setOnlyShowInBili(this@MainActivity, onlyInBiliSwitch.isChecked)
                DramaAccessibilityService.instance?.refreshOverlayVisibility()
                Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(btnSave)

        val logTitle = TextView(this).apply {
            text = "运行日志："
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }
        root.addView(logTitle)

        logView = TextView(this).apply {
            textSize = 12f
        }
        root.addView(logView)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun refreshStatusAndLogs() {
        val enabled = isAccessibilityEnabled()
        val running = DramaAccessibilityService.instance != null
        val newStatus = "无障碍开关: ${if (enabled) "已开启" else "未开启"} | 服务实例: ${if (running) "已连接" else "未运行"}"
        if (statusView.text.toString() != newStatus) {
            statusView.text = newStatus
        }
        val newLogs = DramaAccessibilityService.logs.takeLast(30).joinToString("\n").ifBlank { "暂无日志" }
        if (logView.text.toString() != newLogs) {
            logView.text = newLogs
        }
        val overlayPref = AccPrefs.isOverlayEnabled(this)
        if (overlaySwitch.isChecked != overlayPref) {
            syncingOverlaySwitch = true
            overlaySwitch.isChecked = overlayPref
            syncingOverlaySwitch = false
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, DramaAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
