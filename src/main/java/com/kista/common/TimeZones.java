package com.kista.common;

import java.time.ZoneId;

// KST(Asia/Seoul) 단일 소스 — ZoneId.of("Asia/Seoul") 인라인 반복 금지
public final class TimeZones {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // @Scheduled(zone=...) 등 컴파일타임 상수 String이 필요한 위치 전용
    public static final String KST_ID = "Asia/Seoul";

    private TimeZones() {}
}
