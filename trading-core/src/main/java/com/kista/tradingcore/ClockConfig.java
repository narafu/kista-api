package com.kista.tradingcore;

import com.kista.shared.kernel.TimeZones;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class ClockConfig {

    // 시스템 현재 시각 SSOT — KST 존. 정적 now() 대신 이 빈을 주입한다(가드 HexagonalArchitectureTest)
    @Bean
    Clock clock() {
        return Clock.system(TimeZones.KST);
    }
}
