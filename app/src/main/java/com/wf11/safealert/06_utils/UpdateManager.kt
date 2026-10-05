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
import java.io.InputStream
import java.security.MessageDigest

object UpdateManager {

    // Current version comes from BuildConfig (single source). A hardcoded version would make any Firebase latest
    //   above it look 'new' even on an up-to-date install → the update dialog repeats forever.
    val CURRENT_VERSION: String = BuildConfig.VERSION_NAME
    private const val TAG = "UpdateManager"

    data class UpdateInfo(
        val latest: String,
        val apkUrl: String,
        val changelog: String,
        val forceUpdate: Boolean,
        val apkSha256: String       // APK SHA-256 uploaded by CI; blank = install refused (fail-closed)
    )

    fun checkForUpdate(context: Context, onResult: (UpdateInfo?) -> Unit) {
        // The APK is shared by all sites, so version is a single /version node outside the site roots.
        //   Alerts, beacon sharing and calibration stay per site under the root.
        FirebaseDatabase.getInstance().reference
            .child("version")
            .get()
            .addOnSuccessListener { snap ->
                val latest    = snap.child("latest").getValue(String::class.java)    ?: run { onResult(null); return@addOnSuccessListener }
                val apkUrl    = snap.child("apk_url").getValue(String::class.java)   ?: run { onResult(null); return@addOnSuccessListener }
                val changelog = snap.child("changelog").getValue(String::class.java) ?: ""
                val force     = snap.child("force_update").getValue(Boolean::class.java) ?: false
                val sha256    = snap.child("apk_sha256").getValue(String::class.java) ?: ""

                if (isNewer(latest, CURRENT_VERSION)) {
                    Log.d(TAG, "새 버전 발견: $latest (현재: $CURRENT_VERSION)")
                    onResult(UpdateInfo(latest, apkUrl, changelog, force, sha256))
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

    fun downloadAndInstall(context: Context, apkUrl: String, expectedSha256: String, onProgress: (Int) -> Unit = {}) {
        val fileName = "safealert-update.apk"
        val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        // If the already-downloaded file matches the expected hash, open the install prompt without re-downloading
        //   (e.g. the prompt was closed and tapped again). On mismatch, delete it and download again.
        if (destFile.exists()) {
            val cached = runCatching { destFile.inputStream().use { sha256Hex(it) } }.getOrDefault("")
            if (hashMatches(expectedSha256, cached)) {
                Log.d(TAG, "받아 둔 APK 해시 일치 - 재다운로드 생략, 설치 창 표시")
                installApk(context, destFile)
                return
            }
            destFile.delete()
        }

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

        // Download-complete receiver
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
                        // Integrity check — on mismatch or a blank expected value, delete and refuse install
                        val actual = runCatching { destFile.inputStream().use { sha256Hex(it) } }.getOrDefault("")
                        if (hashMatches(expectedSha256, actual)) {
                            Log.d(TAG, "다운로드 완료, 해시 일치, 설치 시작")
                            installApk(ctx, destFile)
                        } else {
                            Log.e(TAG, "APK 해시 불일치 expected=$expectedSha256 actual=$actual — 설치 거부")
                            destFile.delete()
                            android.widget.Toast.makeText(ctx, "업데이트 파일 검증 실패 — 설치하지 않았습니다", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        Log.e(TAG, "다운로드 실패 status=$status reason=$reason")
                        android.widget.Toast.makeText(ctx, "다운로드 실패 (오류: $reason)", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                cursor.close()
            }
        }
        // Registered on applicationContext: with an Activity context the unregister path is lost if the Activity
        //   dies first; applicationContext lives as long as the process, so the onReceive self-unregister always runs.
        // EXPORTED — the completion broadcast comes from DownloadProvider (not the system uid), so with NOT_EXPORTED
        //   it can be blocked on Android 13+ and the install prompt may never appear. Safe: installs only after an
        //   id match and a SHA-256 match.
        context.applicationContext.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
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

    /** Streaming SHA-256, lowercase hex */
    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Ignores case and surrounding whitespace. False if either side is blank (fail-closed) */
    fun hashMatches(expected: String, actual: String): Boolean {
        val e = expected.trim().lowercase()
        val a = actual.trim().lowercase()
        return e.isNotEmpty() && e == a
    }

    // Compares "1.2.3"-style versions — true if latest > current
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
