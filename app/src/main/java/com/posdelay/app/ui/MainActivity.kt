package com.posdelay.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.posdelay.app.data.NotificationLog
import com.posdelay.app.data.OrderTracker
import com.posdelay.app.databinding.ActivityMainBinding
import com.posdelay.app.service.DelayAccessibilityService
import com.posdelay.app.service.DelayNotificationHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OrderTracker.init(this)
        NotificationLog.init(this)
        observeData()
        setupButtons()
        checkPermissions()
        DelayNotificationHelper.update(this)
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun observeData() {
        OrderTracker.orderCount.observe(this) { count ->
            binding.tvOrderCount.text = count.toString()
            updateStatus(count)
            DelayNotificationHelper.update(this)
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

    private fun setupButtons() {
        // 처리 중 건수 +/-
        binding.btnCountPlus.setOnClickListener { OrderTracker.incrementOrder() }
        binding.btnCountMinus.setOnClickListener { OrderTracker.decrementOrder() }

        // 쿠팡 임계값 +/-
        binding.btnCoupangPlus.setOnClickListener {
            OrderTracker.setCoupangThreshold(OrderTracker.getCoupangThreshold() + 1)
        }
        binding.btnCoupangMinus.setOnClickListener {
            OrderTracker.setCoupangThreshold(OrderTracker.getCoupangThreshold() - 1)
        }

        // 배민 임계값 +/-
        binding.btnBaeminPlus.setOnClickListener {
            OrderTracker.setBaeminThreshold(OrderTracker.getBaeminThreshold() + 1)
        }
        binding.btnBaeminMinus.setOnClickListener {
            OrderTracker.setBaeminThreshold(OrderTracker.getBaeminThreshold() - 1)
        }

        // 지연 시간 +/-
        binding.btnDelayPlus.setOnClickListener {
            OrderTracker.setDelayMinutes(OrderTracker.getDelayMinutes() + 5)
        }
        binding.btnDelayMinus.setOnClickListener {
            OrderTracker.setDelayMinutes(OrderTracker.getDelayMinutes() - 5)
        }

        // ON/OFF 토글
        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            OrderTracker.setEnabled(isChecked)
            DelayNotificationHelper.update(this)
        }

        // 카운터 초기화
        binding.btnReset.setOnClickListener {
            OrderTracker.resetCount()
            Toast.makeText(this, "카운터 초기화", Toast.LENGTH_SHORT).show()
        }

        // 수동 지연 처리
        binding.btnManualDelay.setOnClickListener {
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

        // 권한 설정
        binding.btnPermissions.setOnClickListener { showPermissionMenu() }

        // 알림 로그 보기
        binding.btnViewLog.setOnClickListener { showNotificationLog() }
    }

    private fun showNotificationLog() {
        val logs = NotificationLog.getLogs()
        val message = if (logs.isEmpty()) {
            "아직 감지된 MATE 알림이 없습니다.\n\n알림 접근 권한이 활성화되어 있는지 확인하세요."
        } else {
            logs.take(20).joinToString("\n\n")
        }

        AlertDialog.Builder(this)
            .setTitle("MATE 알림 로그 (최근 ${minOf(logs.size, 20)}건)")
            .setMessage(message)
            .setPositiveButton("확인", null)
            .setNeutralButton("로그 삭제") { _, _ ->
                NotificationLog.clear()
                Toast.makeText(this, "로그 삭제됨", Toast.LENGTH_SHORT).show()
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
        binding.tvNotificationStatus.text = "알림 접근: ${if (notificationEnabled) "활성화" else "비활성화"}"
        binding.tvNotificationStatus.setTextColor(
            if (notificationEnabled) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt()
        )

        val accessibilityEnabled = isAccessibilityEnabled()
        binding.tvAccessibilityStatus.text = "접근성 서비스: ${if (accessibilityEnabled) "활성화" else "비활성화"}"
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
