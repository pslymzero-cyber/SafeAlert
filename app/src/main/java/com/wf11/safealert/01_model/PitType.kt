package com.wf11.safealert.model

import com.wf11.safealert.ble.BleConstants

/**
 * (v1.1.89 SA-1) PIT(Powered Industrial Truck) 장비 종류.
 *
 * 표시 이름 자유 입력을 대체한다. 사람 이름·닉네임이 BLE 송출과 Firebase 경보 로그로
 * 들어가던 경로를, 입력 수단 자체를 없애 구조적으로 막는다. 현장은 종류와 번호를
 * 고르기만 하고, 키보드를 쓰지 않는다.
 *
 * 송출 ID 는 `코드-번호` 두 토큰이다 — `CB-01`, `RT-07`, `HR-99`.
 *   · 5바이트 고정 → BLE 15바이트 예산 중 10바이트가 에코 데이터 쪽에 남는다
 *   · 센터명은 싣지 않는다. 물리적으로 같은 센터 안에서만 서로를 만나고,
 *     센터명은 12자까지 허용돼 함께 실으면 예산을 넘긴다.
 *     Firebase 로그에는 저장 시점에 센터명을 붙여 `WF11-CB-01` 로 남는다.
 *
 * [code] 는 BLE 로 나가는 값이라 **변경 금지**다. 현장에 배포된 기기와 표기가 어긋난다.
 * 새 장비는 아래에 추가만 한다(순서 = 선택 팝업 노출 순서).
 */
enum class PitType(val code: String, val label: String, val category: Int) {
    COUNTER_BALANCE("CB", "C/B (카운터밸런스)",   BleConstants.CAT_FORKLIFT),
    REACH          ("RT", "리치",                BleConstants.CAT_FORKLIFT),
    HIGH_REACH     ("HR", "하이리치",             BleConstants.CAT_FORKLIFT),
    ORDER_PICKER   ("OP", "오더피커",             BleConstants.CAT_FORKLIFT),
    STACKER        ("ST", "스태커",               BleConstants.CAT_FORKLIFT),
    TOW_TRACTOR    ("TT", "견인차 (토우)",         BleConstants.CAT_FORKLIFT),
    EPJ            ("EP", "EPJ (전동 파렛트 잭)",  BleConstants.CAT_EPJ),
    WALKIE         ("WK", "워키 (워키 스태커)",    BleConstants.CAT_EPJ);

    companion object {
        /**
         * 장비 번호 범위 — 팝업 드롭다운이 그대로 쓴다.
         * 상한 99 는 현장 확인을 거친 값이다: 한 센터에 같은 종류가 100대를 넘지 않는다.
         * 2자리 고정이라 송출 ID 가 5바이트로 맞는다. 3자리로 늘리면 BLE 표기와
         * 이미 배포된 기기의 파싱이 어긋나므로, 상한 변경은 전 기기 동시 배포가 전제다.
         */
        const val NO_MIN = 1
        const val NO_MAX = 99

        /** 역할(Category)에 속한 장비만. 지게차 카드에서 EPJ 가 보이면 경보 반경이 어긋난다. */
        fun forCategory(category: Int): List<PitType> = PitType.values().filter { it.category == category }

        fun fromCode(code: String): PitType? = PitType.values().firstOrNull { it.code == code }

        /** `CB-01` — 번호는 항상 2자리. 자릿수가 흔들리면 화면에서 정렬이 깨진다. */
        fun buildId(type: PitType, no: Int): String = "${type.code}-%02d".format(no)

        /**
         * 송출 ID 를 (종류, 번호)로 되돌린다. 팝업을 다시 열 때 직전 선택을 복원하는 데 쓴다.
         * 형식이 맞아도 등록되지 않은 코드면 null — 구버전이 남긴 값을 걸러낸다.
         */
        fun parse(id: String): Pair<PitType, Int>? {
            val m = Regex("^([A-Z]{2})-([0-9]{2})$").find(id.trim().uppercase()) ?: return null
            val type = fromCode(m.groupValues[1]) ?: return null
            val no = m.groupValues[2].toInt()
            if (no !in NO_MIN..NO_MAX) return null
            return type to no
        }
    }
}
