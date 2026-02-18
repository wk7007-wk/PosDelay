package com.posdelay.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.net.Uri
import android.os.Environment
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.annotation.SuppressLint
import kotlin.collections.ArrayDeque
import android.app.Dialog
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.posdelay.app.data.AdActionLog
import com.posdelay.app.data.AdManager
import com.posdelay.app.data.DelayActionLog
import com.posdelay.app.data.NotificationLog
import com.posdelay.app.data.OrderTracker
import com.posdelay.app.data.UsageTracker
import com.posdelay.app.databinding.ActivityMainBinding
import com.posdelay.app.service.AdScheduler
import com.posdelay.app.service.AdWebAutomation
import com.posdelay.app.service.DelayAccessibilityService
import com.posdelay.app.service.DelayNotificationHelper
import com.posdelay.app.service.GistOrderReader
import com.posdelay.app.service.GitHubUpdater

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var adWebAutomation: AdWebAutomation? = null
    private val adActionQueue = ArrayDeque<Pair<AdWebAutomation.Action, Int>>()
    private var pendingBackToBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OrderTracker.init(this)
        NotificationLog.init(this)
        AdManager.init(this)
        AdActionLog.init(this)
        UsageTracker.init(this)
        observeData()
        setupButtons()
        setupAdButtons()
        checkPermissions()
        DelayNotificationHelper.update(this)

        handleScheduledAction(intent)
        GistOrderReader.start(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleScheduledAction(it) }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    /** 스케줄러에서 보낸 광고 자동 동작 처리 */
    private fun handleScheduledAction(intent: Intent) {
        val action = intent.getStringExtra("ad_scheduled_action") ?: return
        intent.removeExtra("ad_scheduled_action")

        val isBackground = action.startsWith("ad_auto_")
        val cnt = OrderTracker.getOrderCount()
        when (action) {
            "ad_off", "ad_auto_off" -> {
                notifyOrToast(isBackground, "${cnt}건 광고끄기")
                if (AdManager.hasCoupangCredentials()) {
                    executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
                }
                if (AdManager.hasBaeminCredentials()) {
                    executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, AdManager.getBaeminReducedAmount())
                }
            }
            "ad_on", "ad_auto_on" -> {
                notifyOrToast(isBackground, "${cnt}건 광고켜기")
                if (AdManager.hasCoupangCredentials()) {
                    executeAdAction(AdWebAutomation.Action.COUPANG_AD_ON)
                }
                if (AdManager.hasBaeminCredentials()) {
                    executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, AdManager.getBaeminAmount())
                }
            }
        }
        // 백그라운드 트리거면 실행 후 이전 앱으로 복귀
        if (isBackground) {
            pendingBackToBackground = true
        }
    }

    /** 백그라운드 트리거 → 알림바, 수동 → Toast */
    private fun notifyOrToast(isBackground: Boolean, message: String) {
        if (isBackground) {
            DelayNotificationHelper.showAdProgress(this, message)
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    // ───────── 기존 데이터 관찰 ─────────

    private fun observeData() {
        OrderTracker.orderCount.observe(this) { count ->
            binding.tvOrderCount.text = count.toString()
            updateStatus(count)
            DelayNotificationHelper.update(this)
            // 주문 밀림 감지 → 플랫폼별 광고 자동 끄기/켜기
            checkPlatformThresholds()
        }

        OrderTracker.coupangThreshold.observe(this) { value ->
            binding.tvCoupangThreshold.text = "${value}건"
            updateStatus(OrderTracker.getOrderCount())
        }

        OrderTracker.baeminThreshold.observe(this) { value ->
            binding.tvBaeminThreshold.text = "${value}건"
            updateStatus(OrderTracker.getOrderCount())
        }

        OrderTracker.delayMinutes.observe(this) { value ->
            binding.tvDelayMinutes.text = "${value}분"
        }

        OrderTracker.enabled.observe(this) { enabled ->
            binding.switchEnable.isChecked = enabled
            updateStatus(OrderTracker.getOrderCount())
        }

        OrderTracker.autoMode.observe(this) { auto ->
            updateModeButtons(auto)
        }

        OrderTracker.lastSyncTime.observe(this) { time ->
            updateSyncTime(time)
            updateMonitorPanel()
        }

        OrderTracker.lastPcSyncTime.observe(this) { time ->
            updatePcSyncTime(time)
            updateMonitorPanel()
        }

        // 광고 관리 LiveData
        AdManager.adEnabled.observe(this) { enabled ->
            binding.switchAdEnable.isChecked = enabled
            binding.layoutAdSection.visibility = if (enabled) View.VISIBLE else View.GONE
        }

        AdManager.baeminAmount.observe(this) { value ->
            binding.tvBaeminAmount.text = "${value}원"
        }

        AdManager.baeminReducedAmount.observe(this) { value ->
            binding.tvBaeminReducedAmount.text = "${value}원"
        }

        AdManager.coupangAdOn.observe(this) { on ->
            binding.tvCoupangAdStatus.text = "상태: ${if (on) "켜짐" else "꺼짐"}"
            binding.tvCoupangAdStatus.setTextColor(
                if (on) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt()
            )
        }

        AdManager.adOffTime.observe(this) { time ->
            binding.btnAdOffTime.text = time
        }

        AdManager.adOnTime.observe(this) { time ->
            binding.btnAdOnTime.text = time
        }

        AdManager.scheduleEnabled.observe(this) { enabled ->
            binding.switchSchedule.isChecked = enabled
        }

        AdManager.orderAutoOffEnabled.observe(this) { enabled ->
            binding.switchOrderAutoOff.isChecked = enabled
        }

        AdManager.coupangAutoEnabled.observe(this) { enabled ->
            binding.switchCoupangAuto.isChecked = enabled
        }

        AdManager.baeminAutoEnabled.observe(this) { enabled ->
            binding.switchBaeminAuto.isChecked = enabled
        }

        AdManager.coupangOffThreshold.observe(this) { value ->
            binding.tvCoupangOffTh.text = "$value"
            binding.tvCoupangThresholdInfo.text = "(${value}/${AdManager.getCoupangOnThreshold()})"
        }
        AdManager.coupangOnThreshold.observe(this) { value ->
            binding.tvCoupangOnTh.text = "$value"
            binding.tvCoupangThresholdInfo.text = "(${AdManager.getCoupangOffThreshold()}/${value})"
        }
        AdManager.baeminOffThreshold.observe(this) { value ->
            binding.tvBaeminOffTh.text = "$value"
            binding.tvBaeminThresholdInfo.text = "(${value}/${AdManager.getBaeminOnThreshold()})"
        }
        AdManager.baeminOnThreshold.observe(this) { value ->
            binding.tvBaeminOnTh.text = "$value"
            binding.tvBaeminThresholdInfo.text = "(${AdManager.getBaeminOffThreshold()}/${value})"
        }

        AdManager.baeminCurrentBid.observe(this) { bid ->
            binding.tvBaeminAdStatus.text = if (bid > 0) "현재: ${bid}원" else "현재: --"
            updateMonitorPanel()
        }

        AdManager.coupangCurrentOn.observe(this) { _ -> updateMonitorPanel() }

        AdManager.lastAdAction.observe(this) { action ->
            binding.tvLastAdAction.text = if (action.isNullOrEmpty()) "마지막 동작: --" else "마지막: $action"
            updateMonitorPanel()
        }
    }

    private fun updateMonitorPanel() {
        // 배민 신호등
        val bid = AdManager.getBaeminCurrentBid()
        val normalAmount = AdManager.getBaeminAmount()
        if (bid > 0) {
            binding.tvMonitorBaemin.text = "${bid}원"
            if (bid >= normalAmount) {
                binding.tvBaeminLight.setTextColor(0xFF2ECC71.toInt()) // 초록 = 정상금액
                binding.tvMonitorBaemin.setTextColor(0xFF2ECC71.toInt())
            } else {
                binding.tvBaeminLight.setTextColor(0xFFE74C3C.toInt()) // 빨강 = 축소금액
                binding.tvMonitorBaemin.setTextColor(0xFFE74C3C.toInt())
            }
        } else {
            binding.tvMonitorBaemin.text = "--"
            binding.tvBaeminLight.setTextColor(0xFF666666.toInt())
            binding.tvMonitorBaemin.setTextColor(0xFFCCCCCC.toInt())
        }

        // 쿠팡 신호등
        val coupangOn = AdManager.coupangCurrentOn.value
        when (coupangOn) {
            true -> {
                binding.tvMonitorCoupang.text = "ON"
                binding.tvCoupangLight.setTextColor(0xFF2ECC71.toInt())
                binding.tvMonitorCoupang.setTextColor(0xFF2ECC71.toInt())
            }
            false -> {
                binding.tvMonitorCoupang.text = "OFF"
                binding.tvCoupangLight.setTextColor(0xFFE74C3C.toInt())
                binding.tvMonitorCoupang.setTextColor(0xFFE74C3C.toInt())
            }
            null -> {
                binding.tvMonitorCoupang.text = "--"
                binding.tvCoupangLight.setTextColor(0xFF666666.toInt())
                binding.tvMonitorCoupang.setTextColor(0xFFCCCCCC.toInt())
            }
        }
    }

    private fun updateSyncTime(time: Long) {
        if (time == 0L) {
            binding.tvLastSync.text = "MATE --"
            binding.tvLastSync.setTextColor(0xFFE74C3C.toInt())
            return
        }
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val elapsed = (System.currentTimeMillis() - time) / 1000
        val agoStr = when {
            elapsed < 60 -> "${elapsed}초"
            elapsed < 3600 -> "${elapsed / 60}분"
            else -> "${elapsed / 3600}시간"
        }
        binding.tvLastSync.text = "MATE ${sdf.format(Date(time))} (${agoStr})"
        binding.tvLastSync.setTextColor(
            if (elapsed < 300) 0xFF2ECC71.toInt()
            else if (elapsed < 600) 0xFFE67E22.toInt()
            else 0xFFE74C3C.toInt()
        )
    }

    private fun updatePcSyncTime(pcTime: Long) {
        if (pcTime == 0L) {
            binding.tvPcSync.text = "PC --"
            binding.tvPcSync.setTextColor(0xFF666666.toInt())
            return
        }
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val elapsed = (System.currentTimeMillis() - pcTime) / 1000
        val agoStr = when {
            elapsed < 60 -> "${elapsed}초"
            elapsed < 3600 -> "${elapsed / 60}분"
            else -> "${elapsed / 3600}시간"
        }
        binding.tvPcSync.text = "PC ${sdf.format(Date(pcTime))} (${agoStr})"
        binding.tvPcSync.setTextColor(
            if (elapsed < 300) 0xFF2ECC71.toInt()
            else if (elapsed < 600) 0xFFE67E22.toInt()
            else 0xFFE74C3C.toInt()
        )
    }

    private fun updateModeButtons(auto: Boolean) {
        if (auto) {
            binding.btnModeAuto.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFE74C3C.toInt())
            binding.btnModeSemi.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF555555.toInt())
        } else {
            binding.btnModeSemi.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF2ECC71.toInt())
            binding.btnModeAuto.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF555555.toInt())
        }
    }

    private fun updateStatus(count: Int) {
        val coupangThreshold = OrderTracker.getCoupangThreshold()
        val baeminThreshold = OrderTracker.getBaeminThreshold()
        val enabled = OrderTracker.isEnabled()

        if (!enabled) {
            binding.tvStatus.text = "모니터링 중지"
            binding.tvStatus.setTextColor(0xFF999999.toInt())
            binding.tvOrderCount.setTextColor(0xFF999999.toInt())
            return
        }

        val minThreshold = minOf(coupangThreshold, baeminThreshold)

        when {
            count >= minThreshold -> {
                val targets = mutableListOf<String>()
                if (count >= coupangThreshold) targets.add("쿠팡")
                if (count >= baeminThreshold) targets.add("배민")
                binding.tvStatus.text = "${targets.joinToString("+")} 지연 필요"
                binding.tvStatus.setTextColor(0xFFE74C3C.toInt())
                binding.tvOrderCount.setTextColor(0xFFE74C3C.toInt())
            }
            count >= minThreshold - 2 -> {
                binding.tvStatus.text = "주의"
                binding.tvStatus.setTextColor(0xFFE67E22.toInt())
                binding.tvOrderCount.setTextColor(0xFFE67E22.toInt())
            }
            else -> {
                binding.tvStatus.text = "정상"
                binding.tvStatus.setTextColor(0xFF2ECC71.toInt())
                binding.tvOrderCount.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    // ───────── 기존 버튼 설정 ─────────

    private fun setupButtons() {
        binding.btnModeSemi.setOnClickListener {
            UsageTracker.track(UsageTracker.MODE_SWITCH)
            OrderTracker.setAutoMode(false)
            Toast.makeText(this, "반자동 모드: 알림으로만 안내", Toast.LENGTH_SHORT).show()
        }
        binding.btnModeAuto.setOnClickListener {
            UsageTracker.track(UsageTracker.MODE_SWITCH)
            AlertDialog.Builder(this)
                .setTitle("자동 모드 전환")
                .setMessage("자동 모드에서는 임계값 도달 시 쿠팡이츠 앱이 자동으로 열리고 준비 지연이 설정됩니다.\n\n화면이 전환될 수 있습니다.")
                .setPositiveButton("전환") { _, _ ->
                    OrderTracker.setAutoMode(true)
                    Toast.makeText(this, "자동 모드: 쿠팡이츠 자동 조작", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        binding.btnCountPlus.setOnClickListener { UsageTracker.track(UsageTracker.ORDER_COUNT); OrderTracker.incrementOrder() }
        binding.btnCountMinus.setOnClickListener { UsageTracker.track(UsageTracker.ORDER_COUNT); OrderTracker.setOrderCount(OrderTracker.getOrderCount() - 1) }

        binding.btnCoupangPlus.setOnClickListener {
            UsageTracker.track(UsageTracker.DELAY_THRESHOLD)
            OrderTracker.setCoupangThreshold(OrderTracker.getCoupangThreshold() + 1)
        }
        binding.btnCoupangMinus.setOnClickListener {
            UsageTracker.track(UsageTracker.DELAY_THRESHOLD)
            OrderTracker.setCoupangThreshold(OrderTracker.getCoupangThreshold() - 1)
        }

        binding.btnBaeminPlus.setOnClickListener {
            UsageTracker.track(UsageTracker.DELAY_THRESHOLD)
            OrderTracker.setBaeminThreshold(OrderTracker.getBaeminThreshold() + 1)
        }
        binding.btnBaeminMinus.setOnClickListener {
            UsageTracker.track(UsageTracker.DELAY_THRESHOLD)
            OrderTracker.setBaeminThreshold(OrderTracker.getBaeminThreshold() - 1)
        }

        binding.btnDelayPlus.setOnClickListener {
            UsageTracker.track(UsageTracker.DELAY_SETTING)
            OrderTracker.setDelayMinutes(OrderTracker.getDelayMinutes() + 5)
        }
        binding.btnDelayMinus.setOnClickListener {
            UsageTracker.track(UsageTracker.DELAY_SETTING)
            OrderTracker.setDelayMinutes(OrderTracker.getDelayMinutes() - 5)
        }

        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            OrderTracker.setEnabled(isChecked)
            DelayNotificationHelper.update(this)
        }

        binding.btnReset.setOnClickListener {
            UsageTracker.track(UsageTracker.RESET_COUNT)
            OrderTracker.resetCount()
            Toast.makeText(this, "카운터 초기화", Toast.LENGTH_SHORT).show()
        }

        binding.btnManualDelay.setOnClickListener {
            UsageTracker.track(UsageTracker.MANUAL_DELAY)
            AlertDialog.Builder(this)
                .setTitle("수동 지연 처리")
                .setMessage("쿠팡이츠에 ${OrderTracker.getDelayMinutes()}분 지연을 설정하시겠습니까?")
                .setPositiveButton("실행") { _, _ ->
                    DelayAccessibilityService.triggerDelay(this)
                    Toast.makeText(this, "쿠팡이츠 지연 처리 시도 중...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        binding.btnPermissions.setOnClickListener { showPermissionMenu() }
        binding.btnViewLog.setOnClickListener { UsageTracker.track(UsageTracker.VIEW_LOG); showNotificationLog() }
        binding.btnUpdate.setOnClickListener { GitHubUpdater(this).checkAndUpdate() }
    }

    // ───────── 광고 관리 버튼 설정 ─────────

    private fun setupAdButtons() {
        // 광고 관리 ON/OFF
        binding.switchAdEnable.setOnCheckedChangeListener { _, isChecked ->
            AdManager.setAdEnabled(isChecked)
            if (isChecked) {
                AdScheduler.scheduleAlarms(this)
            } else {
                AdScheduler.cancelAlarms(this)
            }
        }

        // 배민 광고 금액 +/- (50원 단위)
        binding.btnBaeminAmountPlus.setOnClickListener {
            UsageTracker.track(UsageTracker.AMOUNT_BAEMIN)
            AdManager.setBaeminAmount(AdManager.getBaeminAmount() + 50)
        }
        binding.btnBaeminAmountMinus.setOnClickListener {
            UsageTracker.track(UsageTracker.AMOUNT_BAEMIN)
            AdManager.setBaeminAmount(AdManager.getBaeminAmount() - 50)
        }

        // 배민 축소 금액 +/- (50원 단위)
        binding.btnBaeminReducedPlus.setOnClickListener {
            UsageTracker.track(UsageTracker.AMOUNT_BAEMIN_REDUCED)
            AdManager.setBaeminReducedAmount(AdManager.getBaeminReducedAmount() + 50)
        }
        binding.btnBaeminReducedMinus.setOnClickListener {
            UsageTracker.track(UsageTracker.AMOUNT_BAEMIN_REDUCED)
            AdManager.setBaeminReducedAmount(AdManager.getBaeminReducedAmount() - 50)
        }

        // 쿠팡 광고 켜기/끄기
        binding.btnCoupangAdOn.setOnClickListener {
            UsageTracker.track(UsageTracker.COUPANG_AD_TOGGLE)
            lastCoupangAutoTime = System.currentTimeMillis()
            executeAdAction(AdWebAutomation.Action.COUPANG_AD_ON)
        }
        binding.btnCoupangAdOff.setOnClickListener {
            UsageTracker.track(UsageTracker.COUPANG_AD_TOGGLE)
            lastCoupangAutoTime = System.currentTimeMillis()
            executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
        }

        // 스케줄 ON/OFF
        binding.switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            AdManager.setScheduleEnabled(isChecked)
            AdScheduler.scheduleAlarms(this)
        }

        // 광고 끄기 시간 선택
        binding.btnAdOffTime.setOnClickListener { UsageTracker.track(UsageTracker.SCHEDULE_TIME); showTimePicker(true) }
        binding.btnAdOnTime.setOnClickListener { UsageTracker.track(UsageTracker.SCHEDULE_TIME); showTimePicker(false) }

        // 주문 자동 끄기 ON/OFF
        binding.switchOrderAutoOff.setOnCheckedChangeListener { _, isChecked ->
            AdManager.setOrderAutoOffEnabled(isChecked)
        }
        binding.switchCoupangAuto.setOnCheckedChangeListener { _, isChecked ->
            AdManager.setCoupangAutoEnabled(isChecked)
        }
        binding.switchBaeminAuto.setOnCheckedChangeListener { _, isChecked ->
            AdManager.setBaeminAutoEnabled(isChecked)
        }

        // 쿠팡 임계값
        binding.btnCoupangOffThPlus.setOnClickListener { UsageTracker.track(UsageTracker.AUTO_THRESHOLD_COUPANG); AdManager.setCoupangOffThreshold(AdManager.getCoupangOffThreshold() + 1) }
        binding.btnCoupangOffThMinus.setOnClickListener { UsageTracker.track(UsageTracker.AUTO_THRESHOLD_COUPANG); AdManager.setCoupangOffThreshold(AdManager.getCoupangOffThreshold() - 1) }
        binding.btnCoupangOnThPlus.setOnClickListener { UsageTracker.track(UsageTracker.AUTO_THRESHOLD_COUPANG); AdManager.setCoupangOnThreshold(AdManager.getCoupangOnThreshold() + 1) }
        binding.btnCoupangOnThMinus.setOnClickListener { UsageTracker.track(UsageTracker.AUTO_THRESHOLD_COUPANG); AdManager.setCoupangOnThreshold(AdManager.getCoupangOnThreshold() - 1) }
        // 배민 임계값
        binding.btnBaeminOffThPlus.setOnClickListener { UsageTracker.track(UsageTracker.AUTO_THRESHOLD_BAEMIN); AdManager.setBaeminOffThreshold(AdManager.getBaeminOffThreshold() + 1) }
        binding.btnBaeminOffThMinus.setOnClickListener { UsageTracker.track(UsageTracker.AUTO_THRESHOLD_BAEMIN); AdManager.setBaeminOffThreshold(AdManager.getBaeminOffThreshold() - 1) }
        binding.btnBaeminOnThPlus.setOnClickListener { UsageTracker.track(UsageTracker.AUTO_THRESHOLD_BAEMIN); AdManager.setBaeminOnThreshold(AdManager.getBaeminOnThreshold() + 1) }
        binding.btnBaeminOnThMinus.setOnClickListener { UsageTracker.track(UsageTracker.AUTO_THRESHOLD_BAEMIN); AdManager.setBaeminOnThreshold(AdManager.getBaeminOnThreshold() - 1) }

        // 직접 열기: 전체화면 WebView로 사이트 확인
        binding.btnOpenBaemin.setOnClickListener {
            UsageTracker.track(UsageTracker.OPEN_BAEMIN)
            openWebViewBrowser("배민 광고센터", "https://self.baemin.com/ad-center")
        }
        binding.btnOpenCoupang.setOnClickListener {
            UsageTracker.track(UsageTracker.OPEN_COUPANG)
            openWebViewBrowser("쿠팡이츠 광고", "https://advertising.coupangeats.com")
        }

        // 수동 실행: 배민 정상금액
        binding.btnAdManualBaeminNormal.setOnClickListener {
            UsageTracker.track(UsageTracker.MANUAL_BAEMIN_NORMAL)
            if (!AdManager.hasBaeminCredentials()) {
                Toast.makeText(this, "배민 로그인 정보를 먼저 설정해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastBaeminAutoTime = System.currentTimeMillis() // 수동 → 자동 쿨다운
            executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, AdManager.getBaeminAmount())
        }

        // 수동 실행: 배민 축소금액
        binding.btnAdManualBaeminReduced.setOnClickListener {
            UsageTracker.track(UsageTracker.MANUAL_BAEMIN_REDUCED)
            if (!AdManager.hasBaeminCredentials()) {
                Toast.makeText(this, "배민 로그인 정보를 먼저 설정해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastBaeminAutoTime = System.currentTimeMillis()
            executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, AdManager.getBaeminReducedAmount())
        }

        // 수동 실행: 쿠팡 켜기
        binding.btnAdManualCoupangOn.setOnClickListener {
            UsageTracker.track(UsageTracker.MANUAL_COUPANG_ON)
            if (!AdManager.hasCoupangCredentials()) {
                Toast.makeText(this, "쿠팡 로그인 정보를 먼저 설정해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastCoupangAutoTime = System.currentTimeMillis()
            executeAdAction(AdWebAutomation.Action.COUPANG_AD_ON)
        }

        // 수동 실행: 쿠팡 끄기
        binding.btnAdManualCoupangOff.setOnClickListener {
            UsageTracker.track(UsageTracker.MANUAL_COUPANG_OFF)
            if (!AdManager.hasCoupangCredentials()) {
                Toast.makeText(this, "쿠팡 로그인 정보를 먼저 설정해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastCoupangAutoTime = System.currentTimeMillis()
            executeAdAction(AdWebAutomation.Action.COUPANG_AD_OFF)
        }

        // 로그인 설정
        binding.btnBaeminLogin.setOnClickListener { UsageTracker.track(UsageTracker.LOGIN_BAEMIN); showLoginDialog("배민") }
        binding.btnCoupangLogin.setOnClickListener { UsageTracker.track(UsageTracker.LOGIN_COUPANG); showLoginDialog("쿠팡") }

        // 광고 로그 보기
        binding.btnAdLog.setOnClickListener { UsageTracker.track(UsageTracker.AD_LOG); showAdLog() }
    }

    // ───────── 광고 자동화 실행 (순차 큐) ─────────

    private fun executeAdAction(action: AdWebAutomation.Action, amount: Int = AdManager.getBaeminAmount()) {
        if (adWebAutomation?.isRunning() == true) {
            adActionQueue.addLast(Pair(action, amount))
            val actionName = actionDisplayName(action, amount)
            Toast.makeText(this, "$actionName 대기 ${adActionQueue.size}건", Toast.LENGTH_SHORT).show()
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
                val c = OrderTracker.getOrderCount()
                val result = if (success) "${c}건 $actionName 완료" else "${c}건 $actionName 실패"
                if (pendingBackToBackground || adActionQueue.isEmpty()) {
                    DelayNotificationHelper.showAdResult(this, result, success)
                }
                if (!pendingBackToBackground) {
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
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
        // 이전 WebView 정리 후 약간 대기
        adWebAutomation = null
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startAdAction(nextAction, nextAmount)
        }, 2000)
    }

    private fun actionDisplayName(action: AdWebAutomation.Action, amount: Int): String {
        return when (action) {
            AdWebAutomation.Action.BAEMIN_SET_AMOUNT -> "배민 ${amount}원"
            AdWebAutomation.Action.COUPANG_AD_ON -> "쿠팡 켜기"
            AdWebAutomation.Action.COUPANG_AD_OFF -> "쿠팡 끄기"
        }
    }

    /** 플랫폼별 임계값 체크 — 현재 광고 상태 기반으로 판단 */
    private var lastCoupangAutoTime = 0L
    private var lastBaeminAutoTime = 0L

    private fun checkPlatformThresholds() {
        val count = OrderTracker.getOrderCount()
        val now = System.currentTimeMillis()

        // 쿠팡: 현재 ON인데 끄기 임계값 이상 → 끄기
        //       현재 OFF인데 켜기 임계값 이하 → 켜기
        if (AdManager.hasCoupangCredentials() && now - lastCoupangAutoTime >= 5 * 60 * 1000) {
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

        // 배민: 현재 정상금액인데 끄기 임계값 이상 → 축소
        //       현재 축소금액인데 켜기 임계값 이하 → 정상 복원
        if (AdManager.hasBaeminCredentials() && now - lastBaeminAutoTime >= 5 * 60 * 1000) {
            val bid = AdManager.getBaeminCurrentBid()
            val normalAmount = AdManager.getBaeminAmount()
            if (AdScheduler.shouldBaeminOff() && (bid <= 0 || bid >= normalAmount)) {
                lastBaeminAutoTime = now
                DelayNotificationHelper.showAdProgress(this, "${count}건 배민 ${AdManager.getBaeminReducedAmount()}원")
                executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, AdManager.getBaeminReducedAmount())
            } else if (AdScheduler.shouldBaeminOn() && bid in 1 until normalAmount) {
                lastBaeminAutoTime = now
                DelayNotificationHelper.showAdProgress(this, "${count}건 배민 ${AdManager.getBaeminAmount()}원")
                executeAdAction(AdWebAutomation.Action.BAEMIN_SET_AMOUNT, AdManager.getBaeminAmount())
            }
        }
    }

    // ───────── 직접 열기 (전체화면 WebView 브라우저) ─────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun openWebViewBrowser(title: String, url: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        // WebView를 담을 레이아웃
        val container = FrameLayout(this)

        // 상단 바: URL + 닫기 버튼
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF333333.toInt())
            setPadding(16, 8, 16, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val urlText = TextView(this).apply {
            text = url
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 11f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = android.widget.Button(this).apply {
            text = "닫기"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFE74C3C.toInt())
            setPadding(24, 8, 24, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
        }

        topBar.addView(urlText)
        topBar.addView(closeBtn)

        // WebView 생성
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    super.onPageFinished(view, loadedUrl)
                    urlText.text = loadedUrl
                }
            }
            webChromeClient = WebChromeClient()

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = 100 }
        }

        container.addView(webView)
        container.addView(topBar)

        closeBtn.setOnClickListener {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
            dialog.dismiss()
        }

        dialog.setContentView(container)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.setOnDismissListener {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (_: Exception) {}
        }
        dialog.show()

        webView.loadUrl(url)
        Toast.makeText(this, "$title 로딩 중...", Toast.LENGTH_SHORT).show()
    }

    // ───────── 시간 선택 다이얼로그 ─────────

    private fun showTimePicker(isOffTime: Boolean) {
        val current = if (isOffTime) AdManager.getAdOffTime() else AdManager.getAdOnTime()
        val parts = current.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        TimePickerDialog(this, { _, h, m ->
            val timeStr = String.format("%02d:%02d", h, m)
            if (isOffTime) {
                AdManager.setAdOffTime(timeStr)
            } else {
                AdManager.setAdOnTime(timeStr)
            }
            AdScheduler.scheduleAlarms(this)
        }, hour, minute, true).show()
    }

    // ───────── 로그인 설정 다이얼로그 ─────────

    private fun showLoginDialog(platform: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val idInput = EditText(this).apply {
            hint = "아이디 (이메일)"
            setText(if (platform == "배민") AdManager.getBaeminId() else AdManager.getCoupangId())
        }
        val pwInput = EditText(this).apply {
            hint = "비밀번호"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(idInput)
        layout.addView(pwInput)

        val hasCredentials = if (platform == "배민") AdManager.hasBaeminCredentials() else AdManager.hasCoupangCredentials()
        val statusMsg = if (hasCredentials) "현재: 설정됨" else "현재: 미설정"

        AlertDialog.Builder(this)
            .setTitle("${platform} 로그인 정보")
            .setMessage("${statusMsg}\n웹 포털 로그인에 사용됩니다.\n암호화하여 저장됩니다.")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val id = idInput.text.toString().trim()
                val pw = pwInput.text.toString()
                if (id.isEmpty()) {
                    Toast.makeText(this, "아이디를 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (pw.isEmpty() && !hasCredentials) {
                    Toast.makeText(this, "비밀번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val finalPw = if (pw.isEmpty()) {
                    // 비밀번호 미입력 시 기존 값 유지
                    if (platform == "배민") AdManager.getBaeminPw() else AdManager.getCoupangPw()
                } else pw

                if (platform == "배민") {
                    AdManager.setBaeminCredentials(id, finalPw)
                } else {
                    AdManager.setCoupangCredentials(id, finalPw)
                }
                Toast.makeText(this, "${platform} 로그인 정보 저장됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ───────── 종합 로그 분석 ─────────

    private fun showAdLog() {
        val adLogs = AdActionLog.getLogs()
        val adErrors = AdActionLog.getErrorLogs()
        val delayLogs = DelayActionLog.getLogs()
        val delayErrors = DelayActionLog.getErrorLogs()

        val options = arrayOf(
            "기능 사용 빈도 통계",
            "─── 광고 자동화 ───",
            "광고 전체 로그 (${adLogs.size}건)",
            "광고 에러만 (${adErrors.size}건)",
            "광고 배민만",
            "광고 쿠팡만",
            "광고 HTML 스냅샷",
            "─── 지연 자동화 ───",
            "지연 전체 로그 (${delayLogs.size}건)",
            "지연 에러만 (${delayErrors.size}건)",
            "지연 쿠팡만",
            "지연 배민만",
            "─── 분석/관리 ───",
            "에러 분석 리포트",
            "전체 로그 클립보드 복사",
            "파일로 저장 (/sdcard/Download/)",
            "로그 전체 삭제"
        )
        AlertDialog.Builder(this)
            .setTitle("로그 분석 (광고 ${adLogs.size} + 지연 ${delayLogs.size}건)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showUsageStats()
                    // 1 = separator
                    2 -> showLogViewer("광고 전체 로그", adLogs)
                    3 -> showLogViewer("광고 에러 로그", adErrors)
                    4 -> showLogViewer("광고 배민 로그", adLogs.filter { it.contains("배민") })
                    5 -> showLogViewer("광고 쿠팡 로그", adLogs.filter { it.contains("쿠팡") })
                    6 -> showLogViewer("HTML 스냅샷", adLogs.filter { it.contains("[H01]") })
                    // 7 = separator
                    8 -> showLogViewer("지연 전체 로그", delayLogs)
                    9 -> showLogViewer("지연 에러 로그", delayErrors)
                    10 -> showLogViewer("지연 쿠팡 로그", delayLogs.filter { it.contains("쿠팡") })
                    11 -> showLogViewer("지연 배민 로그", delayLogs.filter { it.contains("배민") || it.contains("MATE") })
                    // 12 = separator
                    13 -> showErrorAnalysis()
                    14 -> copyAllLogsToClipboard()
                    15 -> saveLogsToFile()
                    16 -> {
                        AlertDialog.Builder(this)
                            .setTitle("로그 전체 삭제")
                            .setMessage("광고 로그 + 지연 로그 + 알림 로그를 모두 삭제합니다.")
                            .setPositiveButton("삭제") { _, _ ->
                                AdActionLog.clear()
                                DelayActionLog.clear()
                                NotificationLog.clear()
                                Toast.makeText(this, "모든 로그 삭제됨", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }
                }
            }
            .show()
    }

    /** 사용 빈도 통계 표시 */
    private fun showUsageStats() {
        val report = UsageTracker.getReport()
        val textView = TextView(this).apply {
            text = report
            textSize = 13f
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(30, 20, 30, 20)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scrollView = ScrollView(this).apply { addView(textView) }

        AlertDialog.Builder(this)
            .setTitle("기능 사용 빈도")
            .setView(scrollView)
            .setPositiveButton("확인", null)
            .setNeutralButton("복사") { _, _ ->
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("사용빈도", report))
                Toast.makeText(this, "사용 빈도 통계 복사됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("초기화") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("사용 빈도 통계를 초기화하시겠습니까?")
                    .setPositiveButton("초기화") { _, _ ->
                        UsageTracker.clear()
                        Toast.makeText(this, "사용 빈도 초기화됨", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .show()
    }

    /** 스크롤 가능한 로그 뷰어 */
    private fun showLogViewer(title: String, logs: List<String>, maxShow: Int = 50) {
        val display = logs.take(maxShow)

        if (display.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("로그가 없습니다.")
                .setPositiveButton("확인", null)
                .show()
            return
        }

        val content = display.joinToString("\n\n")

        // 스크롤 가능한 TextView
        val textView = TextView(this).apply {
            text = content
            textSize = 11f
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(30, 20, 30, 20)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply {
            addView(textView)
            setPadding(10, 10, 10, 10)
        }

        AlertDialog.Builder(this)
            .setTitle("$title (${display.size}/${logs.size}건)")
            .setView(scrollView)
            .setPositiveButton("확인", null)
            .setNeutralButton("복사") { _, _ ->
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("로그", content))
                Toast.makeText(this, "${display.size}건 클립보드 복사됨", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 에러 분석 리포트 */
    private fun showErrorAnalysis() {
        val sb = StringBuilder()

        // 광고 에러 분석
        sb.append("=== 광고 자동화 에러 분석 ===\n\n")
        val adLogs = AdActionLog.getLogs()
        val adErrors = AdActionLog.getErrorLogs()
        sb.append("총 로그: ${adLogs.size}건, 에러: ${adErrors.size}건\n")
        if (adErrors.isNotEmpty()) {
            val adStats = mutableMapOf<String, Int>()
            val codeRegex = Regex("""\[(E\d+|H\d+)\]""")
            for (log in adLogs) {
                val match = codeRegex.find(log) ?: continue
                val code = match.groupValues[1]
                if (code.startsWith("E")) {
                    adStats[code] = (adStats[code] ?: 0) + 1
                }
            }
            for ((code, count) in adStats.entries.sortedByDescending { it.value }) {
                sb.append("  $code (${AdActionLog.Code.describe(code)}): ${count}건\n")
            }
            sb.append("\n")
            // 해결 제안
            if (adStats.containsKey("E01")) sb.append("E01: 배민/쿠팡 로그인 정보를 설정하세요\n")
            if (adStats.containsKey("E02")) sb.append("E02: 인터넷 연결을 확인하세요. 포털 점검 중일 수 있습니다\n")
            if (adStats.containsKey("E03")) sb.append("E03: 로그인 페이지 구조가 변경됨. HTML 스냅샷을 확인하세요\n")
            if (adStats.containsKey("E04")) sb.append("E04: 아이디/비밀번호를 확인하세요. 쿠팡은 SMS 인증 필요\n")
            if (adStats.containsKey("E05")) sb.append("E05: 광고 관리 페이지를 찾을 수 없음. 메뉴 구조 변경 가능\n")
            if (adStats.containsKey("E06")) sb.append("E06: 광고 토글을 찾을 수 없음. 페이지 구조 확인 필요\n")
            if (adStats.containsKey("E07")) sb.append("E07: 시간 초과. 인터넷 속도 또는 포털 응답 느림\n")
        } else {
            sb.append("  에러 없음\n")
        }

        sb.append("\n=== 지연 자동화 에러 분석 ===\n\n")
        val delayLogs = DelayActionLog.getLogs()
        val delayErrors = DelayActionLog.getErrorLogs()
        sb.append("총 로그: ${delayLogs.size}건, 에러: ${delayErrors.size}건\n")
        if (delayErrors.isNotEmpty()) {
            val delayStats = mutableMapOf<String, Int>()
            val codeRegex2 = Regex("""\[(D\d+)\]""")
            for (log in delayLogs) {
                val match = codeRegex2.find(log) ?: continue
                val code = match.groupValues[1]
                delayStats[code] = (delayStats[code] ?: 0) + 1
            }
            for ((code, count) in delayStats.entries.sortedByDescending { it.value }) {
                sb.append("  $code (${DelayActionLog.Code.describe(code)}): ${count}건\n")
            }
            sb.append("\n")
            if (delayStats.containsKey("D01")) sb.append("D01: 접근성 서비스를 활성화하세요\n")
            if (delayStats.containsKey("D02")) sb.append("D02: 쿠팡이츠 앱이 정상 실행되는지 확인\n")
            if (delayStats.containsKey("D03")) sb.append("D03: 처리 중인 주문이 있는지 확인\n")
            if (delayStats.containsKey("D04")) sb.append("D04: 이미 지연이 적용된 주문일 수 있음\n")
            if (delayStats.containsKey("D07")) sb.append("D07: 앱 응답이 느림. 기기 성능 확인\n")
            if (delayStats.containsKey("D08")) sb.append("D08: MATE 앱에 '조리시간 추가' 가능한 주문이 없음\n")
            if (delayStats.containsKey("D10")) sb.append("D10: 화면 접근 불가. 접근성 서비스 재활성화\n")
            if (delayStats.containsKey("D11")) sb.append("D11: 대상 앱이 설치되어 있는지 확인\n")
        } else {
            sb.append("  에러 없음\n")
        }

        // 최근 성공 기록
        sb.append("\n=== 최근 성공 기록 ===\n")
        val adSuccesses = adLogs.filter { it.contains("[OK]") }.take(5)
        val delaySuccesses = delayLogs.filter { it.contains("[DI04]") }.take(5)
        if (adSuccesses.isEmpty() && delaySuccesses.isEmpty()) {
            sb.append("성공 기록 없음\n")
        } else {
            for (s in adSuccesses) sb.append("  광고: ${s.trim()}\n")
            for (s in delaySuccesses) sb.append("  지연: ${s.trim()}\n")
        }

        val textView = TextView(this).apply {
            text = sb.toString()
            textSize = 12f
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(30, 20, 30, 20)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        AlertDialog.Builder(this)
            .setTitle("에러 분석 리포트")
            .setView(scrollView)
            .setPositiveButton("확인", null)
            .setNeutralButton("복사") { _, _ ->
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("에러분석", sb.toString()))
                Toast.makeText(this, "분석 리포트 복사됨", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 로그를 파일로 저장 */
    private fun saveLogsToFile() {
        val sb = StringBuilder()
        sb.append("=== PosDelay 로그 덤프 ===\n")
        sb.append("시간: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())}\n\n")

        sb.append("--- 광고 자동화 로그 (${AdActionLog.getLogs().size}건) ---\n")
        AdActionLog.getLogs().forEach { sb.append("$it\n") }

        sb.append("\n--- HTML 스냅샷 ---\n")
        AdActionLog.getLogs().filter { it.contains("[H01]") }.forEach { sb.append("$it\n") }

        sb.append("\n--- 지연 자동화 로그 (${DelayActionLog.getLogs().size}건) ---\n")
        DelayActionLog.getLogs().forEach { sb.append("$it\n") }

        sb.append("\n--- MATE 알림 로그 (${NotificationLog.getLogs().size}건) ---\n")
        NotificationLog.getLogs().forEach { sb.append("$it\n") }

        try {
            val file = File("/sdcard/Download/PosDelay_log.txt")
            file.writeText(sb.toString())
            Toast.makeText(this, "저장 완료: ${file.absolutePath} (${sb.length}자)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // fallback: 앱 내부 → 외부 복사
            try {
                val internal = File(filesDir, "PosDelay_log.txt")
                internal.writeText(sb.toString())
                val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PosDelay_log.txt")
                internal.copyTo(dest, overwrite = true)
                Toast.makeText(this, "저장 완료: ${dest.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(this, "저장 실패: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 전체 로그 클립보드 복사 */
    private fun copyAllLogsToClipboard() {
        val sb = StringBuilder()
        sb.append("=== PosDelay 로그 덤프 ===\n")
        sb.append("시간: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())}\n\n")

        sb.append("--- 광고 자동화 로그 (${AdActionLog.getLogs().size}건) ---\n")
        AdActionLog.getLogs().take(100).forEach { sb.append("$it\n") }

        sb.append("\n--- 지연 자동화 로그 (${DelayActionLog.getLogs().size}건) ---\n")
        DelayActionLog.getLogs().take(100).forEach { sb.append("$it\n") }

        sb.append("\n--- MATE 알림 로그 (${NotificationLog.getLogs().size}건) ---\n")
        NotificationLog.getLogs().take(50).forEach { sb.append("$it\n") }

        val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("PosDelay전체로그", sb.toString()))
        Toast.makeText(this, "전체 로그 클립보드 복사됨 (${sb.length}자)", Toast.LENGTH_SHORT).show()
    }

    // ───────── 기존 메서드들 ─────────

    private fun installUpdate() {
        // 1. 설치 권한 확인
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "설치 권한을 허용해주세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        // 2. 저장소 읽기 권한 확인 (Android 6~12)
        if (Build.VERSION.SDK_INT in 23..32) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 200)
                Toast.makeText(this, "저장소 읽기 권한이 필요합니다", Toast.LENGTH_LONG).show()
                return
            }
        }

        // 3. APK 파일 찾기 (여러 경로 시도)
        val possiblePaths = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PosDelay.apk"),
            File("/sdcard/Download/PosDelay.apk"),
            File("/storage/emulated/0/Download/PosDelay.apk")
        )
        val srcFile = possiblePaths.firstOrNull { it.exists() && it.canRead() }

        if (srcFile == null) {
            val paths = possiblePaths.joinToString("\n") { "  ${it.absolutePath} (존재: ${it.exists()}, 읽기: ${it.canRead()})" }
            AlertDialog.Builder(this)
                .setTitle("APK 파일 없음")
                .setMessage("다음 경로에서 PosDelay.apk를 찾을 수 없습니다:\n\n$paths\n\nAPK 파일을 Download 폴더에 복사해주세요.")
                .setPositiveButton("확인", null)
                .show()
            return
        }

        // 4. 캐시에 복사 후 설치
        val cacheApk = File(cacheDir, "PosDelay.apk")
        try {
            srcFile.copyTo(cacheApk, overwrite = true)
            Toast.makeText(this, "APK 복사 완료 (${srcFile.length() / 1024}KB)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("APK 복사 실패")
                .setMessage("원본: ${srcFile.absolutePath}\n대상: ${cacheApk.absolutePath}\n\n오류: ${e.message}\n\n저장소 권한을 확인해주세요.")
                .setPositiveButton("확인", null)
                .show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cacheApk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("설치 실행 실패")
                .setMessage("오류: ${e.message}\n\n'알 수 없는 앱 설치' 권한을 확인해주세요.")
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private fun showNotificationLog() {
        val logs = NotificationLog.getLogs()
        val delayLogs = DelayActionLog.getLogs()

        val options = arrayOf(
            "MATE 알림 로그 (${logs.size}건)",
            "지연 자동화 로그 (${delayLogs.size}건)",
            "지연 에러만 (${DelayActionLog.getErrorLogs().size}건)",
            "에러 분석 리포트"
        )
        AlertDialog.Builder(this)
            .setTitle("모니터링 로그")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showLogViewer("MATE 알림 로그", logs)
                    1 -> showLogViewer("지연 자동화 로그", delayLogs)
                    2 -> showLogViewer("지연 에러 로그", DelayActionLog.getErrorLogs())
                    3 -> showErrorAnalysis()
                }
            }
            .show()
    }

    private fun showPermissionMenu() {
        val options = arrayOf("알림 접근 권한", "접근성 서비스 권한", "알림 표시 권한 (Android 13+)")
        AlertDialog.Builder(this)
            .setTitle("권한 설정")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    2 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
                        } else {
                            Toast.makeText(this, "Android 13 미만에서는 불필요합니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun checkPermissions() {
        val notificationEnabled = isNotificationListenerEnabled()
        binding.tvNotificationStatus.text = if (notificationEnabled) "알림 ✓" else "알림 ✗"
        binding.tvNotificationStatus.setTextColor(
            if (notificationEnabled) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt()
        )

        val accessibilityEnabled = isAccessibilityEnabled()
        binding.tvAccessibilityStatus.text = if (accessibilityEnabled) "접근성 ✓" else "접근성 ✗"
        binding.tvAccessibilityStatus.setTextColor(
            if (accessibilityEnabled) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt()
        )
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}
