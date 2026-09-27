package com.wf11.safealert.firebase

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * (v1.1.98) 디버그 빌드 — 디버그 제공자. 강제가 켜지면 이 빌드는 로그에 찍히는 디버그 토큰을
 * Firebase 콘솔(App Check → 디버그 토큰 관리)에 등록해야 통과한다. 릴리스 빌드는 src/release 쪽.
 */
internal object AppCheckProviders {
    fun factory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
}
