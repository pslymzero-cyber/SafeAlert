package com.wf11.safealert.firebase

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** (v1.1.98) 릴리스 빌드 — Play Integrity 로 앱(서명)과 기기를 증명한다. 디버그 빌드는 src/debug 쪽. */
internal object AppCheckProviders {
    fun factory(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()
}
