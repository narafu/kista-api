package com.kista.shared.support;

import com.kista.shared.kernel.TimeZones;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;

// 슬라이스 테스트용 고정 Clock — 앱 루트 ClockConfig는 슬라이스가 로드하지 않는다
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockTestConfig {

    // KST 2026-01-06(화) 12:00 — 미국 비DST
    public static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-06T03:00:00Z"), TimeZones.KST);

    @Bean
    public Clock clock() {
        return FIXED;
    }
}
