package com.posdelay.app.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 로그를 /sdcard/Download/PosDelay_log.txt에 자동 저장
 * 외부에서 바로 읽을 수 있도록 실시간 기록
 */
object LogFileWriter {

    private val logFile = File("/sdcard/Download/PosDelay_log.txt")
    private const val MAX_FILE_SIZE = 500_000L  // 500KB 초과 시 앞부분 삭제
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)

    /** 로그 한 줄 추가 (자동 호출) */
    fun append(source: String, entry: String) {
        try {
            // 파일 크기 관리
            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                trimFile()
            }
            val time = dateFormat.format(Date())
            logFile.appendText("[$source] [$time] $entry\n")
        } catch (_: Exception) {
            // /sdcard 접근 실패 시 무시
        }
    }

    /** 전체 덤프 저장 (수동 호출 시) */
    fun writeFull(content: String) {
        try {
            logFile.writeText(content)
        } catch (_: Exception) {}
    }

    /** 파일 앞 절반 삭제 */
    private fun trimFile() {
        try {
            val lines = logFile.readLines()
            val keep = lines.drop(lines.size / 2)
            logFile.writeText("--- 로그 자동 정리: ${dateFormat.format(Date())} ---\n")
            logFile.appendText(keep.joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }
}
