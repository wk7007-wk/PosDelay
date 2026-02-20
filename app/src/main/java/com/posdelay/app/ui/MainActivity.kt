package com.posdelay.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
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
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.collections.ArrayDeque
import java.util.Locale
import com.posdelay.app.R
import com.posdelay.app.data.AdActionLog
import com.posdelay.app.data.AdManager
import com.posdelay.app.data.OrderTracker
import com.posdelay.app.data.UsageTracker
import com.posdelay.app.service.AdScheduler
import com.posdelay.app.service.AdWebAutomation
import com.posdelay.app.service.DelayNotificationHelper
import com.posdelay.app.service.FirebaseKdsReader
import com.posdelay.app.service.FirebaseSettingsSync
import com.posdelay.app.service.GistOrderReader

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile var isInForeground = false
            private set
    }

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var adWebAutomation: AdWebAutomation? = null
    private val adActionQueue = ArrayDeque<Pair<AdWebAutomation.Action, Int>>()
    private var pendingBackToBackground = false
    private var pendingRefreshCorrection = false
    private var lastCoupangAutoTime = 0L
    private var lastBaeminAutoTime = 0L

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

        tts = TextToSpeech(this, this)

        // 기존 서비스 시작
        GistOrderReader.start(this)
        FirebaseKdsReader.start(this)
        DelayNotificationHelper.update(this)

        // 건수 변경 감지 → 광고 자동화
        OrderTracker.orderCount.observe(this) { count ->
            DelayNotificationHelper.update(this)
            checkPlatformThresholds()
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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.KOREA
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
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
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "ck_${System.currentTimeMillis()}")
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
        val cnt = OrderTracker.getOrderCount()
        val actionName = actionDisplayName(action, amount)
        if (pendingBackToBackground) {
            DelayNotificationHelper.showAdProgress(this, "${cnt}건 $actionName")
        } else {
            Toast.makeText(this, "${cnt}건 $actionName", Toast.LENGTH_SHORT).show()
        }

        adWebAutomation = AdWebAutomation(this)
        adWebAutomation?.execute(action, amount) { success, message ->
            runOnUiThread {
                val isCheck = action == AdWebAutomation.Action.BAEMIN_CHECK || action == AdWebAutomation.Action.COUPANG_CHECK
                val c = OrderTracker.getOrderCount()
                val result = if (success) "${c}건 $actionName 완료" else "${c}건 $actionName 실패"
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
            if (pendingRefreshCorrection) {
                evaluateAndCorrect()
                return
            }
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

    // ═══════ 스케줄 / 자동 광고 ═══════

    private fun handleScheduledAction(action: String) {
        val isBackground = action.startsWith("ad_auto_")
        val cnt = OrderTracker.getOrderCount()
        when (action) {
            "ad_off", "ad_auto_off" -> {
                notify(isBackground, "${cnt}건 광고끄기")
                if (AdManager.hasCoupangCredentials()) executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
                if (AdManager.hasBaeminCredentials()) {
                    val target = if (isBackground) calculateBaeminTarget() ?: AdManager.getBaeminReducedAmount()
                    else AdManager.getBaeminReducedAmount()
                    executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, target)
                }
            }
            "ad_on", "ad_auto_on" -> {
                notify(isBackground, "${cnt}건 광고켜기")
                if (AdManager.hasCoupangCredentials()) executeAdAction(AdWebAutomation.Action.COUPANG_AD_ON)
                if (AdManager.hasBaeminCredentials()) {
                    val target = if (isBackground) calculateBaeminTarget() ?: AdManager.getBaeminAmount()
                    else AdManager.getBaeminAmount()
                    executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, target)
                }
            }
        }
        if (isBackground) pendingBackToBackground = true
    }

    private fun notify(isBackground: Boolean, message: String) {
        if (isBackground) DelayNotificationHelper.showAdProgress(this, message)
        else Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun calculateBaeminTarget(): Int? = when {
        AdScheduler.shouldBaeminOff() -> AdManager.getBaeminReducedAmount()
        AdScheduler.shouldBaeminMid() -> AdManager.getBaeminMidAmount()
        AdScheduler.shouldBaeminOn() -> AdManager.getBaeminAmount()
        else -> null
    }

    private fun checkPlatformThresholds() {
        if (!AdManager.isOrderAutoOffEnabled()) return
        val count = OrderTracker.getOrderCount()
        val now = System.currentTimeMillis()

        if (AdManager.isCoupangAutoEnabled() && AdManager.hasCoupangCredentials() && now - lastCoupangAutoTime >= 5 * 60 * 1000) {
            val isOn = AdManager.coupangCurrentOn.value
            if (AdScheduler.shouldCoupangOff() && isOn != false) {
                lastCoupangAutoTime = now
                DelayNotificationHelper.showAdProgress(this, "${count}건 쿠팡 끄기")
                executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
            } else if (AdScheduler.shouldCoupangOn() && isOn == false) {
                lastCoupangAutoTime = now
                DelayNotificationHelper.showAdProgress(this, "${count}건 쿠팡 켜기")
                executeAdAction(AdWebAutomation.Action.COUPANG_AD_ON)
            }
        }

        if (AdManager.isBaeminAutoEnabled() && AdManager.hasBaeminCredentials() && now - lastBaeminAutoTime >= 5 * 60 * 1000) {
            val bid = AdManager.getBaeminCurrentBid()
            val targetAmount = calculateBaeminTarget()
            if (targetAmount != null && bid > 0 && bid != targetAmount) {
                lastBaeminAutoTime = now
                DelayNotificationHelper.showAdProgress(this, "${count}건 배민 ${targetAmount}원")
                executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, targetAmount)
            }
        }
    }

    // ═══════ 서버 상태 확인 + 정정 ═══════

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
        pendingRefreshCorrection = true
        Toast.makeText(this, "서버 상태 확인 중...", Toast.LENGTH_SHORT).show()
        if (hasBaemin) executeAdAction(AdWebAutomation.Action.BAEMIN_CHECK)
        if (hasCoupang) executeAdAction(AdWebAutomation.Action.COUPANG_CHECK)
    }

    private fun evaluateAndCorrect() {
        pendingRefreshCorrection = false
        val count = OrderTracker.getOrderCount()

        if (AdManager.hasBaeminCredentials()) {
            val bid = AdManager.getBaeminCurrentBid()
            val bZone = AdManager.getBaeminZoneAt(count)
            val targetAmount = when (bZone) {
                3 -> AdManager.getBaeminReducedAmount()
                2 -> AdManager.getBaeminMidAmount()
                1 -> AdManager.getBaeminAmount()
                else -> null
            }
            if (targetAmount != null && bid > 0 && bid != targetAmount) {
                executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, targetAmount)
            }
        }

        if (AdManager.hasCoupangCredentials()) {
            val isOn = AdManager.coupangCurrentOn.value
            val cZone = AdManager.getCoupangZoneAt(count)
            if (cZone == 2 && isOn != false) {
                executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
            } else if (cZone == 1 && isOn != true) {
                executeAdAction(AdWebAutomation.Action.COUPANG_AD_ON)
            }
        }
    }
}
