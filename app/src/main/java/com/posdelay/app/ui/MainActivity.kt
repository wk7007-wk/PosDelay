package com.posdelay.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.collections.ArrayDeque
import com.posdelay.app.R
import com.posdelay.app.data.AdActionLog
import com.posdelay.app.data.AdManager
import com.posdelay.app.data.OrderTracker
import com.posdelay.app.data.UsageTracker
import com.posdelay.app.service.AdDecisionEngine
import com.posdelay.app.service.AdScheduler
import com.posdelay.app.service.AdWebAutomation
import com.posdelay.app.service.DelayNotificationHelper
import com.posdelay.app.service.FirebaseKdsReader
import com.posdelay.app.service.FirebaseSettingsSync
import com.posdelay.app.service.GistOrderReader

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile var isInForeground = false
            private set
    }

    private lateinit var webView: WebView
    private var adWebAutomation: AdWebAutomation? = null
    private val adActionQueue = ArrayDeque<Pair<AdWebAutomation.Action, Int>>()
    private var pendingBackToBackground = false
    private var lastAutoEvalTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.addJavascriptInterface(NativeBridge(), "NativeBridge")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.setBackgroundColor(0xFF121225.toInt())
        webView.loadUrl("https://wk7007-wk.github.io/PosKDS/")

        // 화면 꺼짐 방지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Wake Lock (CPU 절전 방지)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PosDelay:SSE").apply { acquire() }

        // WiFi Lock (WiFi 절전 방지)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PosDelay:SSE").apply { acquire() }

        // 기존 서비스 시작
        GistOrderReader.start(this)
        FirebaseKdsReader.start(this)
        DelayNotificationHelper.update(this)

        // 건수 변경 감지 → 광고 자동화 (Firebase에서 최신 설정 조회 후 판단)
        OrderTracker.orderCount.observe(this) { _ ->
            DelayNotificationHelper.update(this)
            evaluateAndExecute("건수변동", background = false)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.getStringExtra("ad_scheduled_action") ?: return
        intent.removeExtra("ad_scheduled_action")
        handleScheduledAction(action)
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    // ═══════ JavaScript Bridge ═══════

    inner class NativeBridge {

        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" }
        }

        @JavascriptInterface
        fun getPermissions(): String {
            val notif = isNotificationListenerEnabled()
            val delay = isAccessibilityEnabled("DelayAccessibilityService")
            val kds = isAccessibilityEnabled("KdsAccessibilityService")
            return """{"notification":$notif,"delay":$delay,"kds":$kds}"""
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        @JavascriptInterface
        fun openNotificationSettings() {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        @JavascriptInterface
        fun openAppSettings() {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        }

        @JavascriptInterface
        fun requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        @JavascriptInterface
        fun executeAd(action: String, amount: Int) {
            runOnUiThread {
                val adAction = when (action) {
                    "BAEMIN_SET_AMOUNT" -> AdWebAutomation.Action.BAEMIN_SET_AMOUNT
                    "COUPANG_AD_ON" -> AdWebAutomation.Action.COUPANG_AD_ON
                    "COUPANG_AD_OFF" -> AdWebAutomation.Action.COUPANG_AD_OFF
                    "BAEMIN_CHECK" -> AdWebAutomation.Action.BAEMIN_CHECK
                    "COUPANG_CHECK" -> AdWebAutomation.Action.COUPANG_CHECK
                    else -> return@runOnUiThread
                }
                executeAdAction(adAction, amount)
            }
        }

        @JavascriptInterface
        fun showLoginDialog(platform: String) {
            runOnUiThread { showCredentialDialog(platform) }
        }

        @JavascriptInterface
        fun hasCredentials(platform: String): Boolean {
            return if (platform == "baemin") AdManager.hasBaeminCredentials()
            else AdManager.hasCoupangCredentials()
        }

        @JavascriptInterface
        fun refreshAndCorrect() {
            runOnUiThread { doRefreshAndCorrect() }
        }

        @JavascriptInterface
        fun trackUsage(key: String) {
            UsageTracker.track(key)
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }

        @JavascriptInterface
        fun toggleSource(source: String) {
            runOnUiThread {
                when (source) {
                    "kds" -> OrderTracker.setKdsPaused(!OrderTracker.isKdsPaused())
                    "mate" -> OrderTracker.setMatePaused(!OrderTracker.isMatePaused())
                    "pc" -> OrderTracker.setPcPaused(!OrderTracker.isPcPaused())
                }
                FirebaseSettingsSync.onOrderCountChanged()
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            DelayNotificationHelper.showAdAlert(this@MainActivity, text)
        }

        @JavascriptInterface
        fun getSourceStatus(): String {
            return """{"kds_paused":${OrderTracker.isKdsPaused()},"mate_paused":${OrderTracker.isMatePaused()},"pc_paused":${OrderTracker.isPcPaused()},"kds_sync":${OrderTracker.getLastKdsSyncTime()},"mate_sync":${OrderTracker.getLastSyncTime()},"pc_sync":${OrderTracker.getLastPcSyncTime()}}"""
        }
    }

    // ═══════ 권한 확인 ═══════

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun isAccessibilityEnabled(serviceName: String): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name.contains(serviceName)
        }
    }

    // ═══════ 로그인 다이얼로그 ═══════

    private fun showCredentialDialog(platform: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etId = EditText(this).apply {
            hint = "아이디"
            setText(if (platform == "baemin") AdManager.getBaeminId() else AdManager.getCoupangId())
        }
        val etPw = EditText(this).apply {
            hint = "비밀번호"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etId)
        layout.addView(etPw)

        val title = if (platform == "baemin") "배민 로그인" else "쿠팡 로그인"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val id = etId.text.toString().trim()
                val pw = etPw.text.toString().trim()
                if (id.isNotEmpty() && pw.isNotEmpty()) {
                    if (platform == "baemin") AdManager.setBaeminCredentials(id, pw)
                    else AdManager.setCoupangCredentials(id, pw)
                    Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ═══════ 광고 자동화 (네이티브 전용) ═══════

    private fun executeAdAction(action: AdWebAutomation.Action, amount: Int = AdManager.getBaeminAmount()) {
        if (adWebAutomation?.isRunning() == true) {
            adActionQueue.addLast(Pair(action, amount))
            Toast.makeText(this, "${actionDisplayName(action, amount)} 대기 ${adActionQueue.size}건", Toast.LENGTH_SHORT).show()
            return
        }
        startAdAction(action, amount)
    }

    private fun startAdAction(action: AdWebAutomation.Action, amount: Int) {
        val actionName = actionDisplayName(action, amount)
        if (pendingBackToBackground) {
            DelayNotificationHelper.showAdProgress(this, actionName)
        } else {
            Toast.makeText(this, actionName, Toast.LENGTH_SHORT).show()
        }

        adWebAutomation = AdWebAutomation(this)
        adWebAutomation?.execute(action, amount) { success, message ->
            runOnUiThread {
                val isCheck = action == AdWebAutomation.Action.BAEMIN_CHECK || action == AdWebAutomation.Action.COUPANG_CHECK
                val result = if (success) "$actionName 완료" else "$actionName 실패"
                // CHECK 액션은 알림 생략 (변경 액션만 알림)
                if (!isCheck) {
                    if (pendingBackToBackground || adActionQueue.isEmpty()) {
                        DelayNotificationHelper.showAdResult(this, result, success)
                    }
                    if (!pendingBackToBackground) {
                        Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                    }
                    // Firebase 로그도 변경 액션만
                    FirebaseSettingsSync.uploadLog("$result: $message")
                }
                processNextAdAction()
            }
        }
    }

    private fun processNextAdAction() {
        if (adActionQueue.isEmpty()) {
            if (pendingBackToBackground) {
                pendingBackToBackground = false
                moveTaskToBack(true)
            }
            return
        }
        val (nextAction, nextAmount) = adActionQueue.removeFirst()
        adWebAutomation = null
        Handler(Looper.getMainLooper()).postDelayed({
            startAdAction(nextAction, nextAmount)
        }, 2000)
    }

    private fun actionDisplayName(action: AdWebAutomation.Action, amount: Int): String = when (action) {
        AdWebAutomation.Action.BAEMIN_SET_AMOUNT -> "배민 ${amount}원"
        AdWebAutomation.Action.COUPANG_AD_ON -> "쿠팡 켜기"
        AdWebAutomation.Action.COUPANG_AD_OFF -> "쿠팡 끄기"
        AdWebAutomation.Action.BAEMIN_CHECK -> "배민 확인"
        AdWebAutomation.Action.COUPANG_CHECK -> "쿠팡 확인"
    }

    // ═══════ 통합 광고 판단 (AdDecisionEngine 기반) ═══════

    /**
     * 모든 트리거(건수변동/스케줄/백그라운드)에서 호출되는 단일 진입점.
     * Firebase에서 최신 설정 GET → zone→금액 판단 → 필요한 액션만 실행.
     */
    private fun evaluateAndExecute(reason: String, background: Boolean) {
        val now = System.currentTimeMillis()
        // 5분 쿨다운 (너무 자주 실행 방지)
        if (now - lastAutoEvalTime < 5 * 60 * 1000) return
        if (adWebAutomation?.isRunning() == true || adActionQueue.isNotEmpty()) return

        val hasBaemin = AdManager.hasBaeminCredentials()
        val hasCoupang = AdManager.hasCoupangCredentials()
        if (!hasBaemin && !hasCoupang) return

        val currentBid = AdManager.getBaeminCurrentBid()
        val currentCoupangOn = AdManager.coupangCurrentOn.value
        val count = OrderTracker.getOrderCount()

        // 백그라운드 스레드에서 Firebase 조회 후 UI 스레드에서 실행
        kotlin.concurrent.thread {
            val actions = AdDecisionEngine.evaluate(
                count = count,
                currentBaeminBid = currentBid,
                currentCoupangOn = currentCoupangOn,
                hasBaemin = hasBaemin,
                hasCoupang = hasCoupang
            )
            if (actions.isEmpty()) return@thread

            lastAutoEvalTime = now
            runOnUiThread {
                if (background) pendingBackToBackground = true
                for (action in actions) {
                    when (action) {
                        is AdDecisionEngine.AdAction.CoupangOn -> {
                            DelayNotificationHelper.showAdProgress(this, "쿠팡 켜기")
                            executeAdAction(AdWebAutomation.Action.COUPANG_AD_ON)
                        }
                        is AdDecisionEngine.AdAction.CoupangOff -> {
                            DelayNotificationHelper.showAdProgress(this, "쿠팡 끄기")
                            executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
                        }
                        is AdDecisionEngine.AdAction.BaeminSetAmount -> {
                            DelayNotificationHelper.showAdProgress(this, "배민 ${action.amount}원")
                            executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, action.amount)
                        }
                    }
                }
                FirebaseSettingsSync.uploadLog("[$reason] 건수=${count} 액션=${actions.size}개")
            }
        }
    }

    /** 스케줄 알람/백그라운드에서 호출 */
    private fun handleScheduledAction(action: String) {
        val isBackground = action.startsWith("ad_auto_")
        evaluateAndExecute("스케줄:$action", background = isBackground)
    }

    /** 새로고침+정정 (수동) — Firebase 최신 설정 기반 */
    private fun doRefreshAndCorrect() {
        val hasBaemin = AdManager.hasBaeminCredentials()
        val hasCoupang = AdManager.hasCoupangCredentials()
        if (!hasBaemin && !hasCoupang) {
            Toast.makeText(this, "로그인 정보 없음", Toast.LENGTH_SHORT).show()
            return
        }
        if (adWebAutomation?.isRunning() == true || adActionQueue.isNotEmpty()) {
            Toast.makeText(this, "다른 작업 진행 중", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "확인 중...", Toast.LENGTH_SHORT).show()
        // 수동 정정은 쿨다운 무시
        lastAutoEvalTime = 0
        evaluateAndExecute("수동정정", background = false)
    }
}
