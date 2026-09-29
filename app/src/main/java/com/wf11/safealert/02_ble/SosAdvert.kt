package com.wf11.safealert.ble

/**
 * (v1.1.99) 구조 요청(SOS) 광고 확장 — 구조 요청 중에만 서비스 데이터 뒤에 3바이트를 덧붙인다.
 *
 * 서비스 데이터 배치: byte0 = 상태 페이로드(불변), byte1 = 확장 플래그(불변),
 * byte2 = 구조 요청 회차(1..255, 0 = 없음), byte3-4 = 구조 요청자의 최근 비콘 짧은 ID(빅엔디언, 0 = 없음).
 *
 * 하위 호환: v1.1.30 이후 모든 버전은 서비스 데이터를 인덱스로 읽고(byte0, v1.1.62부터 byte1) 길이를
 * 검사하지 않으므로 덧붙인 바이트는 구버전이 무시한다. 제조사 데이터는 구버전이 전체를 ID 로 읽으므로
 * 절대 늘리면 안 된다.
 *
 * 패킷 예산: 논커넥터블 광고라 플래그 AD 가 없다. UUID 목록 4 + 서비스 데이터 (4+길이) + 제조사 (4+ID길이) <= 31.
 * 확장 시 서비스 데이터가 5바이트이므로 ID 는 12바이트까지만 싣는다(실제 ID 는 PIT 5B, AUTO 11B 라 영향 없음).
 */
object SosAdvert {
    const val ID_MAX = 15
    const val EXT_ID_MAX = 12
    const val PACKET_MAX = 31

    /** 광고 패킷 총 바이트: 16비트 서비스 UUID 목록 AD + 서비스 데이터 AD + 제조사 데이터 AD. */
    fun advertBytes(idLen: Int, dataLen: Int): Int = 4 + (4 + dataLen) + (4 + idLen)

    /** 광고에 싣는 ID 바이트. 구조 요청 확장 중에는 12바이트, 아니면 15바이트로 자른다. */
    fun idBytes(deviceId: String, sosExt: Boolean): ByteArray =
        deviceId.toByteArray(Charsets.UTF_8).take(if (sosExt) EXT_ID_MAX else ID_MAX).toByteArray()

    /** 서비스 데이터 조립. 회차 0 이면 기존 2바이트 그대로. */
    fun serviceData(byte0: Byte, ext: Int, episode: Int, hint: Int): ByteArray =
        if (episode == 0) byteArrayOf(byte0, ext.toByte())
        else byteArrayOf(byte0, ext.toByte(), episode.toByte(), (hint shr 8).toByte(), hint.toByte())

    /** byte2 를 부호 없이 읽는다. 없으면 0. 스캔 경로라 할당 없음. */
    fun decodeEpisode(svc: ByteArray?): Int =
        if (svc != null && svc.size > 2) svc[2].toInt() and 0xFF else 0

    /** byte3-4 를 부호 없는 16비트로 읽는다. 하나라도 없으면 0. */
    fun decodeHint(svc: ByteArray?): Int =
        if (svc != null && svc.size > 4) ((svc[3].toInt() and 0xFF) shl 8) or (svc[4].toInt() and 0xFF) else 0

    /** 비콘 키의 짧은 ID. CRC-16/CCITT-FALSE(다항식 0x1021, 초기값 0xFFFF) 대문자 ASCII. 빈 키 0, 결과 0 은 1 로. */
    fun beaconShortId(key: String): Int {
        if (key.isEmpty()) return 0
        var crc = 0xFFFF
        for (ch in key.uppercase()) {
            crc = crc xor ((ch.code and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return nonZero(crc)
    }

    /** 0 은 '없음' 이므로 실제 값 0 은 1 로 옮긴다. */
    fun nonZero(v: Int): Int = if (v == 0) 1 else v
}
