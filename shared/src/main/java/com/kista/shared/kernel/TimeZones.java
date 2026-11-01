package com.kista.shared.kernel;

import java.time.ZoneId;

// KST·NY 단일 소스 — ZoneId.of(...) 인라인 반복 금지(가드 HexagonalArchitectureTest.zoneId_of_must_only_be_called_in_TimeZones)
public final class TimeZones {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static final ZoneId NY = ZoneId.of("America/New_York"); // 미국 동부(뉴욕) — 미국장 세션 계산 기준

    // @Scheduled(zone=...) 등 컴파일타임 상수 String이 필요한 위치 전용
    public static final String KST_ID = "Asia/Seoul";

    private TimeZones() {}
}
