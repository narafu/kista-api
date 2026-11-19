package com.kista.tradingcore;

import com.kista.shared.kernel.TimeZones;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// 테스트 전용 TradingApplication 컨텍스트의 Clock — 운영 Clock은 root ClockConfig 하나다(슬라이스는 이 클래스를 로드하지 않는다)
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    // 시스템 현재 시각 SSOT — KST 존. 정적 now() 대신 이 빈을 주입한다(가드 HexagonalArchitectureTest)
    @Bean
    Clock clock() {
        return Clock.system(TimeZones.KST);
    }
}
