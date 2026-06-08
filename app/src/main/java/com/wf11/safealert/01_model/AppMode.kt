package com.wf11.safealert.model

enum class AppMode {
    DEVICE,  // 장비 모드 - 지게차 등 (Category 로 EPJ/지게차 세분)
    EPJ,     // [v1.0.34] EPJ(전동 파레트 잭) — 광고 prefix 는 DEVICE 와 동일, Category 로 구분
    WALKER,  // 보행자 모드
    IDLE     // 대기 상태
}
