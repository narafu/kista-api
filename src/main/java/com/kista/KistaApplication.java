package com.kista;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// scanBasePackages 명시 — root 테스트 classpath(testImplementation(":trading-core"))에 trading-core
// 전체가 올라오면서 기본 스캔 범위(암묵적 com.kista)가 trading-core 컴포넌트까지 함께 잡아 빈 중복
// 등록을 일으키는 문제 발생. root가 실제로 소유한 패키지 + :shared(sharedkernel/platform)만 명시해
// trading-core 패키지(trading/matching/broker/account/privacy/marketcalendar)는 스캔 범위에서
// 원천 배제 — TradingApplication(:trading-core)이 반대 방향으로 이미 쓰는 것과 동일한 allowlist 패턴
@SpringBootApplication(scanBasePackages = {
        "com.kista.admin",
        "com.kista.finance",
        "com.kista.market",
        "com.kista.notify",
        "com.kista.stats",
        "com.kista.user",
        "com.kista.web",
        "com.kista.sharedkernel",
        "com.kista.platform",
})
@EnableScheduling
public class KistaApplication {
    public static void main(String[] args) {
        SpringApplication.run(KistaApplication.class, args);
    }
}
