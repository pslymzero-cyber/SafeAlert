package com.wf11.safealert.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.firebase.database.FirebaseDatabase
import com.wf11.safealert.BuildConfig
import java.io.File

object UpdateManager {

    // [v1.0.46 #6] 하드코딩 "1.0.0" → BuildConfig 단일 출처. 하드코딩 시절엔 설치본이 최신이어도
    //   Firebase latest 가 1.0.0 보다 크기만 하면 '새 버전' 판정 → 업데이트 다이얼로그 무한 반복.
    val CURRENT_VERSION: String = BuildConfig.VERSION_NAME
    private const val TAG = "UpdateManager"

    data class UpdateInfo(
        val latest: String,
        val apkUrl: String,
        val changelog: String,
        val forceUpdate: Boolean
    )

    fun checkForUpdate(context: Context, onResult: (UpdateInfo?) -> Unit) {
        val root = DevSettings.firebaseRoot
        FirebaseDatabase.getInstance().reference
            .child(root).child("version")
            .get()
            .addOnSuccessListener { snap ->
                val latest    = snap.child("latest").getValue(String::class.java)    ?: run { onResult(null); return@addOnSuccessListener }
                val apkUrl    = snap.child("apk_url").getValue(String::class.java)   ?: run { onResult(null); return@addOnSuccessListener }
                val changelog = snap.child("changelog").getValue(String::class.java) ?: ""
                val force     = snap.child("force_update").getValue(Boolean::class.java) ?: false

                if (isNewer(latest, CURRENT_VERSION)) {
                    Log.d(TAG, "새 버전 발견: $latest (현재: $CURRENT_VERSION)")
                    onResult(UpdateInfo(latest, apkUrl, changelog, force))
                } else {
                    Log.d(TAG, "최신 버전 사용 중: $CURRENT_VERSION")
                    onResult(null)
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "버전 확인 실패: ${it.message}")
                onResult(null)
            }
    }

    fun downloadAndInstall(context: Context, apkUrl: String, onProgress: (Int) -> Unit = {}) {
        val fileName = "safealert-update.apk"
        val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("SafeAlert 업데이트")
            .setDescription("새 버전 다운로드 중...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        Log.d(TAG, "다운로드 시작 id=$downloadId url=$apkUrl")

        // 다운로드 완료 수신
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                ctx.unregisterReceiver(this)

                val query  = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Log.d(TAG, "다운로드 완료, 설치 시작")
                        installApk(ctx, destFile)
                    } else {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        Log.e(TAG, "다운로드 실패 status=$status reason=$reason")
                        android.widget.Toast.makeText(ctx, "다운로드 실패 (오류: $reason)", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                cursor.close()
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) { Log.e(TAG, "APK 파일 없음: ${apkFile.path}"); return }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    // "1.2.3" 형식 비교 — latest > current 이면 true
    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull  { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
