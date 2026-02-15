package com.posdelay.app.service

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class GitHubUpdater(private val activity: Activity) {

    companion object {
        private const val REPO = "wk7007-wk/PosDelay"
        private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val APK_NAME = "PosDelay.apk"
    }

    /** 최신 릴리즈 확인 + 다운로드 + 설치 */
    fun checkAndUpdate() {
        Toast.makeText(activity, "GitHub에서 최신 버전 확인 중...", Toast.LENGTH_SHORT).show()

        thread {
            try {
                val (tagName, apkUrl, body) = fetchLatestRelease()

                activity.runOnUiThread {
                    val currentVersion = try {
                        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
                    } catch (_: Exception) { "?" }

                    AlertDialog.Builder(activity)
                        .setTitle("업데이트 확인")
                        .setMessage(
                            "현재 버전: v$currentVersion\n" +
                            "최신 버전: $tagName\n\n" +
                            "변경사항:\n$body\n\n" +
                            "다운로드하시겠습니까?"
                        )
                        .setPositiveButton("다운로드 + 설치") { _, _ ->
                            downloadAndInstall(apkUrl, tagName)
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("업데이트 확인 실패")
                        .setMessage("GitHub 연결 오류:\n${e.message}\n\n인터넷 연결을 확인해주세요.")
                        .setPositiveButton("확인", null)
                        .show()
                }
            }
        }
    }

    /** GitHub API에서 최신 릴리즈 정보 가져오기 */
    private fun fetchLatestRelease(): Triple<String, String, String> {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            throw Exception("HTTP $responseCode: ${conn.responseMessage}")
        }

        val json = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val release = JSONObject(json)
        val tagName = release.getString("tag_name")
        val body = release.optString("body", "변경사항 없음").take(500)

        // assets에서 .apk 파일 찾기
        val assets = release.getJSONArray("assets")
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.endsWith(".apk")) {
                apkUrl = asset.getString("browser_download_url")
                break
            }
        }

        if (apkUrl.isEmpty()) {
            throw Exception("릴리즈에 APK 파일이 없습니다")
        }

        return Triple(tagName, apkUrl, body)
    }

    /** DownloadManager로 APK 다운로드 + 완료 후 설치 */
    private fun downloadAndInstall(apkUrl: String, version: String) {
        // 설치 권한 확인
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "설치 권한을 허용해주세요", Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }

        // 기존 파일 삭제
        val destFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            APK_NAME
        )
        if (destFile.exists()) destFile.delete()

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("PosDelay $version 다운로드")
            setDescription("GitHub에서 최신 APK 다운로드 중...")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
        }

        val downloadId = dm.enqueue(request)
        Toast.makeText(activity, "다운로드 시작... (알림에서 진행률 확인)", Toast.LENGTH_SHORT).show()

        // 다운로드 완료 감지
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                try {
                    activity.unregisterReceiver(this)
                } catch (_: Exception) {}

                // 다운로드 결과 확인
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusIdx)
                    cursor.close()

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "다운로드 완료! 설치 시작...", Toast.LENGTH_SHORT).show()
                            installApk(destFile)
                        }
                    } else {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "다운로드 실패 (status=$status)", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    /** APK 설치 실행 */
    private fun installApk(apkFile: File) {
        try {
            val cacheApk = File(activity.cacheDir, APK_NAME)
            apkFile.copyTo(cacheApk, overwrite = true)

            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", cacheApk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            AlertDialog.Builder(activity)
                .setTitle("설치 실패")
                .setMessage("오류: ${e.message}")
                .setPositiveButton("확인", null)
                .show()
        }
    }
}
