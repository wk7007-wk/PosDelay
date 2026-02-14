package com.posdelay.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object OrderTracker {

    private const val PREFS_NAME = "pos_delay_prefs"
    private const val KEY_ORDER_COUNT = "order_count"
    private const val KEY_COUPANG_THRESHOLD = "coupang_threshold"
    private const val KEY_BAEMIN_THRESHOLD = "baemin_threshold"
    private const val KEY_DELAY_MINUTES = "delay_minutes"
    private const val KEY_ENABLED = "enabled"

    private lateinit var prefs: SharedPreferences

    private val _orderCount = MutableLiveData(0)
    val orderCount: LiveData<Int> = _orderCount

    private val _coupangThreshold = MutableLiveData(10)
    val coupangThreshold: LiveData<Int> = _coupangThreshold

    private val _baeminThreshold = MutableLiveData(15)
    val baeminThreshold: LiveData<Int> = _baeminThreshold

    private val _delayMinutes = MutableLiveData(10)
    val delayMinutes: LiveData<Int> = _delayMinutes

    private val _enabled = MutableLiveData(true)
    val enabled: LiveData<Boolean> = _enabled

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _orderCount.postValue(prefs.getInt(KEY_ORDER_COUNT, 0))
        _coupangThreshold.postValue(prefs.getInt(KEY_COUPANG_THRESHOLD, 10))
        _baeminThreshold.postValue(prefs.getInt(KEY_BAEMIN_THRESHOLD, 15))
        _delayMinutes.postValue(prefs.getInt(KEY_DELAY_MINUTES, 10))
        _enabled.postValue(prefs.getBoolean(KEY_ENABLED, true))
    }

    fun getOrderCount(): Int = prefs.getInt(KEY_ORDER_COUNT, 0)
    fun getCoupangThreshold(): Int = prefs.getInt(KEY_COUPANG_THRESHOLD, 10)
    fun getBaeminThreshold(): Int = prefs.getInt(KEY_BAEMIN_THRESHOLD, 15)
    fun getDelayMinutes(): Int = prefs.getInt(KEY_DELAY_MINUTES, 10)
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setOrderCount(count: Int) {
        val value = maxOf(0, count)
        prefs.edit().putInt(KEY_ORDER_COUNT, value).apply()
        _orderCount.postValue(value)
    }

    fun incrementOrder() {
        setOrderCount(getOrderCount() + 1)
    }

    fun decrementOrder() {
        setOrderCount(getOrderCount() - 1)
    }

    fun setCoupangThreshold(value: Int) {
        val clamped = value.coerceIn(1, 50)
        prefs.edit().putInt(KEY_COUPANG_THRESHOLD, clamped).apply()
        _coupangThreshold.postValue(clamped)
    }

    fun setBaeminThreshold(value: Int) {
        val clamped = value.coerceIn(1, 50)
        prefs.edit().putInt(KEY_BAEMIN_THRESHOLD, clamped).apply()
        _baeminThreshold.postValue(clamped)
    }

    fun setDelayMinutes(value: Int) {
        val clamped = value.coerceIn(5, 60)
        prefs.edit().putInt(KEY_DELAY_MINUTES, clamped).apply()
        _delayMinutes.postValue(clamped)
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.postValue(value)
    }

    fun resetCount() {
        setOrderCount(0)
    }

    fun shouldDelayCoupang(): Boolean {
        return isEnabled() && getOrderCount() >= getCoupangThreshold()
    }

    fun shouldDelayBaemin(): Boolean {
        return isEnabled() && getOrderCount() >= getBaeminThreshold()
    }
}
