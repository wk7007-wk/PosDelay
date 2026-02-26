package com.posdelay.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
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
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import com.posdelay.app.service.NativeCookAlertChecker
import com.posdelay.app.service.PosDelayKeepAliveService
import androidx.lifecycle.Observer

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile var isInForeground = false
            private set
    }

    private lateinit var webView: WebView
    private var pageLoadStartTime = 0L
    private var pageLoadFailed = false
    private val DASHBOARD_URL = "https://wk7007-wk.github.io/PosKDS/"
    private val CACHE_FILE = "dashboard_cache.html"
    private var adWebAutomation: AdWebAutomation? = null
    private val adActionQueue = ArrayDeque<Pair<AdWebAutomation.Action, Int>>()
    private var pendingBackToBackground = false
    private var pendingForceAdOff = false
    @Volatile private var isEvaluating = false
    @Volatile private var lastAutoEvalTime = 0L
    @Volatile private var lastAutoEvalCount = -1
    private var bgTriggerPending = false  // checkFromBackground 이중 트리거 방지
    private lateinit var countObserver: Observer<Int>  // observeForever용 (백그라운드에서도 동작)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webView.addJavascriptInterface(NativeBridge(), "NativeBridge")
        webView.webViewClient = DashboardWebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                val m = msg ?: return super.onConsoleMessage(msg)
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    FirebaseSettingsSync.uploadLog("[JS에러] ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                }
                return super.onConsoleMessage(msg)
            }
        }
        webView.setBackgroundColor(0xFF121225.toInt())
        webView.loadUrl(DASHBOARD_URL)

        // 화면 꺼짐 방지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 포그라운드 서비스 시작 (WakeLock + WifiLock + 데이터 수집 + 프로세스 유지)
        PosDelayKeepAliveService.start(this)
        DelayNotificationHelper.update(this)

        // 건수 변경 감지 → 광고 자동화 (observeForever: 백그라운드에서도 동작)
        countObserver = Observer { _ ->
            DelayNotificationHelper.update(this)
            if (bgTriggerPending) return@Observer
            evaluateAndExecute("건수변동", background = !isInForeground)
        }
        OrderTracker.orderCount.observeForever(countObserver)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        // 스케줄 알람 액션
        intent.getStringExtra("ad_scheduled_action")?.let { action ->
            intent.removeExtra("ad_scheduled_action")
            handleScheduledAction(action)
            return
        }
        // 웹 원격 명령 (Firebase command SSE → Intent)
        intent.getStringExtra("remote_ad_command")?.let { action ->
            val amount = intent.getIntExtra("remote_ad_amount", 0)
            intent.removeExtra("remote_ad_command")
            intent.removeExtra("remote_ad_amount")
            handleRemoteCommand(action, amount)
        }
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
        OrderTracker.orderCount.removeObserver(countObserver)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    // ═══════ WebView 로딩 관리 ═══════

    inner class DashboardWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            pageLoadStartTime = System.currentTimeMillis()
            pageLoadFailed = false
            webLog("로딩 시작: $url")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (!pageLoadFailed) {
                val duration = System.currentTimeMillis() - pageLoadStartTime
                webLog("로딩 완료: ${duration}ms")
                // 성공 시 로컬 캐시 저장
                view?.evaluateJavascript("document.documentElement.outerHTML") { html ->
                    if (html != null && html.length > 500) saveDashboardCache(html)
                }
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?,
                                     error: WebResourceError?) {
            // 메인 페이지 로딩 실패만 처리 (서브 리소스 무시)
            if (request?.isForMainFrame == true) {
                pageLoadFailed = true
                val code = if (android.os.Build.VERSION.SDK_INT >= 23) error?.errorCode else -1
                val desc = if (android.os.Build.VERSION.SDK_INT >= 23) error?.description else "unknown"
                webLog("로딩 실패: code=$code desc=$desc → 캐시 fallback")
                loadFromCache()
            }
        }
    }

    private fun loadFromCache() {
        try {
            val file = java.io.File(filesDir, CACHE_FILE)
            if (file.exists()) {
                val html = file.readText()
                webView.loadDataWithBaseURL(DASHBOARD_URL, html, "text/html", "UTF-8", null)
                Toast.makeText(this, "오프라인 모드 (캐시)", Toast.LENGTH_LONG).show()
                webLog("캐시 로드 성공: ${file.length() / 1024}KB")
            } else {
                webView.loadData(
                    "<html><body style='background:#121225;color:#E0E0EC;text-align:center;padding:40px;font-family:sans-serif'>" +
                    "<h2>서버 연결 실패</h2><p>인터넷 연결을 확인하고<br>새로고침 버튼을 눌러주세요</p>" +
                    "<button onclick='location.reload()' style='padding:12px 24px;background:#2ECC71;color:#FFF;border:none;border-radius:8px;font-size:16px;margin-top:20px'>새로고침</button>" +
                    "</body></html>",
                    "text/html", "UTF-8"
                )
                webLog("캐시 없음 → 오류 페이지 표시")
            }
        } catch (e: Exception) {
            webLog("캐시 로드 실패: ${e.message}")
        }
    }

    private fun saveDashboardCache(rawHtml: String) {
        kotlin.concurrent.thread {
            try {
                // evaluateJavascript 결과는 JSON 문자열 (따옴표+이스케이프)
                val html = rawHtml.trim().removeSurrounding("\"")
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\/", "/")
                    .replace("\\\\", "\\").replace("\\u003C", "<")
                    .replace("\\u003E", ">").replace("\\u0026", "&")
                if (html.length < 500) return@thread
                java.io.File(filesDir, CACHE_FILE).writeText(html)
            } catch (_: Exception) {}
        }
    }

    private fun webLog(msg: String) {
        android.util.Log.d("PosDelay-WebView", msg)
        FirebaseSettingsSync.uploadLog("[WebView] $msg")
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
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val battery = pm.isIgnoringBatteryOptimizations(packageName)
            return """{"notification":$notif,"delay":$delay,"kds":$kds,"battery":$battery}"""
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
        fun openBatterySettings() {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
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

        /** 전체 새로고침: SSE 재연결 + KDS 즉시 조회 + 설정 리로드 + 웹 최신 로드 */
        @JavascriptInterface
        fun forceRefresh() {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "새로고침...", Toast.LENGTH_SHORT).show()
                FirebaseKdsReader.restart()
                FirebaseSettingsSync.restart()
                FirebaseKdsReader.fetchOnce()
                DelayNotificationHelper.update(this@MainActivity)
                webView.loadUrl(DASHBOARD_URL)
            }
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
                }
                FirebaseSettingsSync.onOrderCountChanged()
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            DelayNotificationHelper.showAdAlert(this@MainActivity, text)
        }

        @JavascriptInterface
        fun speakCook(text: String) {
            // 백그라운드 복귀 시 밀린 알림 방지 (네이티브 체커가 처리)
            if (!isInForeground) return
            DelayNotificationHelper.showCookAlert(this@MainActivity, text)
        }

        @JavascriptInterface
        fun syncCookSettings(on: Boolean, targetSec: Int, midSec: Int, finishSec: Int) {
            NativeCookAlertChecker.syncSettings(on, targetSec, midSec, finishSec)
        }

        /** 설정 전체 JSON 반환 (웹 UI 표시용) */
        @JavascriptInterface
        fun getSettings(): String {
            return org.json.JSONObject().apply {
                put("ad_enabled", AdManager.isAdEnabled())
                put("baemin_amount", AdManager.getBaeminAmount())
                put("baemin_reduced_amount", AdManager.getBaeminReducedAmount())
                put("baemin_mid_amount", AdManager.getBaeminMidAmount())
                put("coupang_ad_on", AdManager.isCoupangAdOn())
                put("schedule_enabled", AdManager.isScheduleEnabled())
                put("ad_off_time", AdManager.getAdOffTime())
                put("ad_on_time", AdManager.getAdOnTime())
                put("order_auto_off_enabled", AdManager.isOrderAutoOffEnabled())
                put("coupang_auto_enabled", AdManager.isCoupangAutoEnabled())
                put("baemin_auto_enabled", AdManager.isBaeminAutoEnabled())
                put("coupang_zones", AdManager.getZonesJson("coupang"))
                put("baemin_zones", AdManager.getZonesJson("baemin"))
                put("delay_minutes", OrderTracker.getDelayMinutes())
                put("baemin_target_time", AdManager.getBaeminTargetTime())
                put("baemin_fixed_cook_time", AdManager.getBaeminFixedCookTime())
                put("baemin_delay_threshold", AdManager.getBaeminDelayThreshold())
                put("coupang_target_time", AdManager.getCoupangTargetTime())
                put("coupang_fixed_cook_time", AdManager.getCoupangFixedCookTime())
                put("coupang_delay_threshold", AdManager.getCoupangDelayThreshold())
            }.toString()
        }

        /** 설정 개별 저장 (웹 UI → SharedPreferences 직접) */
        @JavascriptInterface
        fun setSetting(key: String, value: String) {
            runOnUiThread {
                when (key) {
                    "ad_enabled" -> AdManager.setAdEnabled(value.toBoolean())
                    "baemin_amount" -> AdManager.setBaeminAmount(value.toInt())
                    "baemin_reduced_amount" -> AdManager.setBaeminReducedAmount(value.toInt())
                    "baemin_mid_amount" -> AdManager.setBaeminMidAmount(value.toInt())
                    "coupang_ad_on" -> AdManager.setCoupangAdOn(value.toBoolean())
                    "schedule_enabled" -> AdManager.setScheduleEnabled(value.toBoolean())
                    "ad_off_time" -> AdManager.setAdOffTime(value)
                    "ad_on_time" -> AdManager.setAdOnTime(value)
                    "order_auto_off_enabled" -> AdManager.setOrderAutoOffEnabled(value.toBoolean())
                    "coupang_auto_enabled" -> AdManager.setCoupangAutoEnabled(value.toBoolean())
                    "baemin_auto_enabled" -> AdManager.setBaeminAutoEnabled(value.toBoolean())
                    "delay_minutes" -> OrderTracker.setDelayMinutes(value.toInt())
                    "baemin_target_time" -> AdManager.setBaeminTargetTime(value.toInt())
                    "baemin_fixed_cook_time" -> AdManager.setBaeminFixedCookTime(value.toInt())
                    "baemin_delay_threshold" -> AdManager.setBaeminDelayThreshold(value.toInt())
                    "coupang_target_time" -> AdManager.setCoupangTargetTime(value.toInt())
                    "coupang_fixed_cook_time" -> AdManager.setCoupangFixedCookTime(value.toInt())
                    "coupang_delay_threshold" -> AdManager.setCoupangDelayThreshold(value.toInt())
                    "coupang_zones" -> AdManager.setZonesFromJson("coupang", org.json.JSONArray(value))
                    "baemin_zones" -> AdManager.setZonesFromJson("baemin", org.json.JSONArray(value))
                    "kds_paused" -> OrderTracker.setKdsPaused(value.toBoolean())
                }
            }
        }

        @JavascriptInterface
        fun getSourceStatus(): String {
            return """{"kds_paused":${OrderTracker.isKdsPaused()},"kds_sync":${OrderTracker.getLastKdsSyncTime()}}"""
        }

        @JavascriptInterface
        fun captureScreen() {
            runOnUiThread {
                try {
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        webView.width, webView.height, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    webView.draw(canvas)
                    val file = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        ), "PosDelay_capture.png"
                    )
                    java.io.FileOutputStream(file).use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, it)
                    }
                    bitmap.recycle()
                    android.widget.Toast.makeText(this@MainActivity, "캡처 저장됨", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "캡처 실패", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
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
            // 중복 검사: 동일 액션이 이미 큐에 있으면 스킵 (BAEMIN은 금액 업데이트)
            val existingIdx = adActionQueue.indexOfFirst { it.first == action }
            if (existingIdx >= 0) {
                if (action == AdWebAutomation.Action.BAEMIN_SET_AMOUNT) {
                    adActionQueue[existingIdx] = Pair(action, amount)  // 최신 금액으로 교체
                }
                return  // 이미 큐에 있으므로 중복 추가 안 함
            }
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
                // 이상신호 감지 기록 (CHECK 제외)
                if (!isCheck) {
                    com.posdelay.app.service.AnomalyDetector.recordExecution(action.name, success)
                }
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
            if (pendingForceAdOff) {
                pendingForceAdOff = false  // Bug 5: 큐 비면 강제 끄기 재시도
                forceAdOff()
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
        AdWebAutomation.Action.BAEMIN_SET_AMOUNT -> "배민광고 ${amount}원"
        AdWebAutomation.Action.COUPANG_AD_ON -> "쿠팡광고 켜기"
        AdWebAutomation.Action.COUPANG_AD_OFF -> "쿠팡광고 끄기"
        AdWebAutomation.Action.BAEMIN_CHECK -> "배민확인"
        AdWebAutomation.Action.COUPANG_CHECK -> "쿠팡확인"
    }

    // ═══════ 통합 광고 판단 (AdDecisionEngine 기반) ═══════

    /**
     * 모든 트리거(건수변동/스케줄/백그라운드)에서 호출되는 단일 진입점.
     * Firebase에서 최신 설정 GET → zone→금액 판단 → 필요한 액션만 실행.
     */
    private fun evaluateAndExecute(reason: String, background: Boolean) {
        if (isEvaluating) return  // 동시 실행 방지
        val now = System.currentTimeMillis()
        val count = OrderTracker.getOrderCount()
        // 건수 변동 시 쿨다운 무시, 동일 건수면 2분 쿨다운
        val countChanged = count != lastAutoEvalCount
        if (!countChanged && now - lastAutoEvalTime < 2 * 60 * 1000) return
        if (adWebAutomation?.isRunning() == true || adActionQueue.isNotEmpty()) return

        val hasBaemin = AdManager.hasBaeminCredentials()
        val hasCoupang = AdManager.hasCoupangCredentials()
        if (!hasBaemin && !hasCoupang) return

        val currentBid = AdManager.getBaeminCurrentBid()
        val currentCoupangOn = AdManager.coupangCurrentOn.value

        isEvaluating = true
        bgTriggerPending = false  // Fix 2: 평가 시작 → 이중 트리거 방지 해제
        // 백그라운드 스레드에서 Firebase 조회 후 UI 스레드에서 실행
        kotlin.concurrent.thread {
            try {
                // 1) 대기열에서 실행 가능한 액션 먼저 드레인
                val pendingActions = AdDecisionEngine.drainPendingQueue(
                    count = count,
                    currentBaeminBid = currentBid,
                    currentCoupangOn = currentCoupangOn,
                    hasBaemin = hasBaemin,
                    hasCoupang = hasCoupang
                )
                if (pendingActions.isNotEmpty()) {
                    android.util.Log.d("AdEval", "대기열 드레인: ${pendingActions.size}개 실행")
                    com.posdelay.app.data.LogFileWriter.append("AD", "대기열 드레인 ${pendingActions.size}개 실행")
                    lastAutoEvalTime = System.currentTimeMillis()
                    lastAutoEvalCount = count
                    runOnUiThread {
                        try {
                            if (background) pendingBackToBackground = true
                            for (action in pendingActions) {
                                AdDecisionEngine.recordExecution(action)
                                when (action) {
                                    is AdDecisionEngine.AdAction.CoupangOn -> {
                                        DelayNotificationHelper.showAdProgress(this, "쿠팡광고 켜기(대기열)")
                                        executeAdAction(AdWebAutomation.Action.COUPANG_AD_ON)
                                    }
                                    is AdDecisionEngine.AdAction.CoupangOff -> {
                                        DelayNotificationHelper.showAdProgress(this, "쿠팡광고 끄기(대기열)")
                                        executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
                                    }
                                    is AdDecisionEngine.AdAction.BaeminSetAmount -> {
                                        DelayNotificationHelper.showAdProgress(this, "배민광고 ${action.amount}원(대기열)")
                                        executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, action.amount)
                                    }
                                }
                            }
                            FirebaseSettingsSync.uploadLog("[$reason] 대기열드레인 건수=$count 액션=${pendingActions.size}개")
                        } finally {
                            isEvaluating = false
                            schedulePendingQueueCheck()
                        }
                    }
                    return@thread
                }

                // 2) 새로운 액션 평가
                val actions = AdDecisionEngine.evaluate(
                    count = count,
                    currentBaeminBid = currentBid,
                    currentCoupangOn = currentCoupangOn,
                    hasBaemin = hasBaemin,
                    hasCoupang = hasCoupang
                )
                if (actions.isEmpty()) {
                    lastAutoEvalCount = count
                    runOnUiThread {
                        isEvaluating = false
                        schedulePendingQueueCheck()  // 대기열에 항목 추가됐을 수 있음
                    }
                    return@thread
                }

                // 10초 안정화 대기 (재시도 1회: 건수 변동 시 새 건수로 재판단)
                android.util.Log.d("AdEval", "액션 ${actions.size}개 대기 (10초 안정화, 건수=$count)")
                com.posdelay.app.data.LogFileWriter.append("AD", "10초 안정화 대기 건수=$count 액션=${actions.size}개")
                Thread.sleep(10_000L)
                var confirmedCount = OrderTracker.getOrderCount()
                var finalActions = actions
                if (confirmedCount != count) {
                    // 건수 변동 → 새 건수로 재판단 (같은 zone이면 실행)
                    val retryActions = AdDecisionEngine.evaluate(
                        count = confirmedCount,
                        currentBaeminBid = currentBid,
                        currentCoupangOn = currentCoupangOn,
                        hasBaemin = hasBaemin,
                        hasCoupang = hasCoupang
                    )
                    if (retryActions.isEmpty()) {
                        android.util.Log.d("AdEval", "안정화: $count→$confirmedCount, 재판단 액션없음 → 취소")
                        com.posdelay.app.data.LogFileWriter.append("AD", "안정화 ${count}→${confirmedCount} 재판단→액션없음")
                        runOnUiThread { isEvaluating = false }
                        return@thread
                    }
                    android.util.Log.d("AdEval", "안정화: $count→$confirmedCount, 재판단 액션=${retryActions.size}개 → 실행")
                    com.posdelay.app.data.LogFileWriter.append("AD", "안정화 ${count}→${confirmedCount} 재판단→${retryActions.size}개 실행")
                    finalActions = retryActions
                    // 5초 추가 대기 후 최종 확인
                    Thread.sleep(5_000L)
                    val finalCount = OrderTracker.getOrderCount()
                    if (finalCount != confirmedCount) {
                        android.util.Log.d("AdEval", "2차 안정화 실패: $confirmedCount→$finalCount, 취소")
                        com.posdelay.app.data.LogFileWriter.append("AD", "2차안정화실패 ${confirmedCount}→${finalCount} 취소")
                        runOnUiThread { isEvaluating = false }
                        return@thread
                    }
                    confirmedCount = finalCount
                }

                lastAutoEvalTime = System.currentTimeMillis()
                lastAutoEvalCount = confirmedCount
                val execActions = finalActions
                val execCount = confirmedCount
                runOnUiThread {
                    try {
                        if (background) pendingBackToBackground = true
                        for (action in execActions) {
                            AdDecisionEngine.recordExecution(action)
                            when (action) {
                                is AdDecisionEngine.AdAction.CoupangOn -> {
                                    DelayNotificationHelper.showAdProgress(this, "쿠팡광고 켜기")
                                    executeAdAction(AdWebAutomation.Action.COUPANG_AD_ON)
                                }
                                is AdDecisionEngine.AdAction.CoupangOff -> {
                                    DelayNotificationHelper.showAdProgress(this, "쿠팡광고 끄기")
                                    executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
                                }
                                is AdDecisionEngine.AdAction.BaeminSetAmount -> {
                                    DelayNotificationHelper.showAdProgress(this, "배민광고 ${action.amount}원")
                                    executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, action.amount)
                                }
                            }
                        }
                        FirebaseSettingsSync.uploadLog("[$reason] 건수=${execCount} 액션=${execActions.size}개 (안정화확인)")
                    } finally {
                        isEvaluating = false
                        schedulePendingQueueCheck()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { isEvaluating = false }  // Fix 1: 예외도 UI 스레드에서 해제
            }
        }
    }

    // 대기열 체크 스케줄러
    private val pendingQueueHandler = Handler(Looper.getMainLooper())
    private var pendingQueueRunnable: Runnable? = null

    /** 대기열에 항목이 있으면 실행 시각에 맞춰 재평가 스케줄링 */
    private fun schedulePendingQueueCheck() {
        // 기존 스케줄 취소
        pendingQueueRunnable?.let { pendingQueueHandler.removeCallbacks(it) }

        val nextTime = AdDecisionEngine.getNextPendingTime()
        if (nextTime <= 0L) return  // 대기열 비어있음

        val delay = (nextTime - System.currentTimeMillis()).coerceAtLeast(1000L)
        android.util.Log.d("AdEval", "대기열 스케줄: ${delay / 1000}초 후 재평가 (${AdDecisionEngine.pendingCount()}개 대기)")
        com.posdelay.app.data.LogFileWriter.append("AD", "대기열 ${delay / 1000}초 후 재평가 예약 (${AdDecisionEngine.pendingCount()}개)")

        val runnable = Runnable {
            android.util.Log.d("AdEval", "대기열 스케줄 트리거 → evaluateAndExecute")
            // 2분 쿨다운 우회: lastAutoEvalTime 리셋
            lastAutoEvalTime = 0
            evaluateAndExecute("대기열", background = false)
        }
        pendingQueueRunnable = runnable
        pendingQueueHandler.postDelayed(runnable, delay)
    }

    /** 웹 UI에서 Firebase 경유로 수신한 원격 명령 실행 */
    private fun handleRemoteCommand(action: String, amount: Int) {
        when (action) {
            "COUPANG_AD_ON" -> executeAdAction(AdWebAutomation.Action.COUPANG_AD_ON)
            "COUPANG_AD_OFF" -> executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
            "BAEMIN_SET_AMOUNT" -> executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, amount)
            "BAEMIN_CHECK" -> executeAdAction(AdWebAutomation.Action.BAEMIN_CHECK)
            "COUPANG_CHECK" -> executeAdAction(AdWebAutomation.Action.COUPANG_CHECK)
            "REFRESH_CORRECT" -> doRefreshAndCorrect()
            "AD_OFF" -> forceAdOff()
            "AD_ON" -> {
                lastAutoEvalTime = 0
                lastAutoEvalCount = -1
                evaluateAndExecute("웹:켜기", background = false)
            }
        }
    }

    /** 스케줄 알람/백그라운드에서 호출 */
    private fun handleScheduledAction(action: String) {
        when (action) {
            "ad_off" -> forceAdOff()
            "ad_on" -> {
                lastAutoEvalTime = 0
                lastAutoEvalCount = -1
                evaluateAndExecute("스케줄:켜기", background = false)
            }
            else -> {
                val isBackground = action.startsWith("ad_auto_")
                if (isBackground) bgTriggerPending = true  // Fix 2: LiveData 이중 트리거 방지
                evaluateAndExecute("스케줄:$action", background = isBackground)
            }
        }
    }

    /** 스케줄 종료 → 강제 광고 끄기 (쿠팡 OFF + 배민 최소) */
    private fun forceAdOff() {
        if (adWebAutomation?.isRunning() == true || adActionQueue.isNotEmpty()) {
            pendingForceAdOff = true  // Bug 5: 작업 완료 후 재시도
            return
        }
        val hasCoupang = AdManager.hasCoupangCredentials()
        val hasBaemin = AdManager.hasBaeminCredentials()
        if (!hasCoupang && !hasBaemin) return

        FirebaseSettingsSync.uploadLog("[스케줄:끄기] 강제 광고 종료")
        if (hasCoupang) {
            DelayNotificationHelper.showAdProgress(this, "쿠팡 끄기")
            executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
        }
        if (hasBaemin) {
            val minAmount = AdManager.getBaeminReducedAmount()
            DelayNotificationHelper.showAdProgress(this, "배민 ${minAmount}원")
            executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, minAmount)
        }
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
