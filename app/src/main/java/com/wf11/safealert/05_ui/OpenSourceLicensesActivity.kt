package com.wf11.safealert.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wf11.safealert.BuildConfig
import com.wf11.safealert.databinding.ActivityOpenSourceLicensesBinding
import com.wf11.safealert.databinding.ItemOssEntryBinding

/**
 * 오픈소스 라이선스 고지 화면
 *
 * Apache License 2.0 등 고지 의무가 있는 라이브러리의
 * 명칭, 버전, 라이선스를 한 화면에 표시.
 */
class OpenSourceLicensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOpenSourceLicensesBinding

    // (라이브러리명, 버전, 라이선스)
    private data class OssEntry(val name: String, val version: String, val license: String)

    private val JETPACK_ENTRIES = listOf(
        OssEntry("AndroidX Core KTX",          "1.12.0",  "Apache 2.0"),
        OssEntry("AndroidX AppCompat",          "1.6.1",   "Apache 2.0"),
        OssEntry("Material Components",         "1.11.0",  "Apache 2.0"),
        OssEntry("ConstraintLayout",            "2.1.4",   "Apache 2.0"),
        OssEntry("RecyclerView",                "1.3.2",   "Apache 2.0"),
        OssEntry("Lifecycle Service",           "2.7.0",   "Apache 2.0"),
        OssEntry("Lifecycle Runtime KTX",       "2.7.0",   "Apache 2.0"),
    )

    private val KOTLIN_ENTRIES = listOf(
        OssEntry("Kotlin",                      "1.9.22",  "Apache 2.0"),
        OssEntry("Kotlinx Coroutines Android",  "1.7.3",   "Apache 2.0"),
    )

    private val FIREBASE_ENTRIES = listOf(
        OssEntry("Firebase Realtime Database",  "32.7.2*", "Apache 2.0"),
        OssEntry("Firebase Analytics",          "32.7.2*", "Apache 2.0"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOpenSourceLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "오픈소스 라이선스"
            setDisplayHomeAsUpEnabled(true)
        }

        binding.tvOslVersion.text = "SafeAlert v${BuildConfig.VERSION_NAME}"

        // Jetpack 항목
        bindEntry(binding.ossCoreKtx,           JETPACK_ENTRIES[0])
        bindEntry(binding.ossAppcompat,         JETPACK_ENTRIES[1])
        bindEntry(binding.ossMaterial,          JETPACK_ENTRIES[2])
        bindEntry(binding.ossConstraintlayout,  JETPACK_ENTRIES[3])
        bindEntry(binding.ossRecyclerview,      JETPACK_ENTRIES[4])
        bindEntry(binding.ossLifecycleService,  JETPACK_ENTRIES[5])
        bindEntry(binding.ossLifecycleRuntime,  JETPACK_ENTRIES[6])

        // Kotlin
        bindEntry(binding.ossKotlin,            KOTLIN_ENTRIES[0])
        bindEntry(binding.ossCoroutines,        KOTLIN_ENTRIES[1])

        // Firebase
        bindEntry(binding.ossFirebaseDatabase,  FIREBASE_ENTRIES[0])
        bindEntry(binding.ossFirebaseAnalytics, FIREBASE_ENTRIES[1])
    }

    private fun bindEntry(item: ItemOssEntryBinding, entry: OssEntry) {
        item.tvOssName.text    = entry.name
        item.tvOssVersion.text = "v${entry.version}"
        item.tvOssLicense.text = entry.license
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
