package com.posdelay.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NotificationLog {

    private const val PREFS_NAME = "notification_log"
    private const val KEY_LOG = "log_entries"
    private const val MAX_ENTRIES = 100

    private lateinit var prefs: SharedPreferences
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private val _logs = MutableLiveData<List<String>>(emptyList())
    val logs: LiveData<List<String>> = _logs

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadLogs()
    }

    fun add(packageName: String, title: String, text: String) {
        val time = dateFormat.format(Date())
        val entry = "[$time] $packageName\n  제목: $title\n  내용: $text"

        val current = getLogs().toMutableList()
        current.add(0, entry)
        if (current.size > MAX_ENTRIES) {
            current.subList(MAX_ENTRIES, current.size).clear()
        }

        prefs.edit().putString(KEY_LOG, current.joinToString("\n===\n")).apply()
        _logs.postValue(current)
    }

    fun getLogs(): List<String> {
        val raw = prefs.getString(KEY_LOG, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("\n===\n")
    }

    fun clear() {
        prefs.edit().remove(KEY_LOG).apply()
        _logs.postValue(emptyList())
    }

    private fun loadLogs() {
        _logs.postValue(getLogs())
    }
}
