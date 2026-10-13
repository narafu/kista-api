package com.kista.tradingcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

// kista-trading 프로세스 부팅 진입점 — 루트 패키지 com.kista.tradingcore에 두어 컴포넌트·엔티티·리포지토리 스캔이
// 기본값(자기 루트)으로 trading-core 전체를 덮는다. :shared 빈은 PlatformAutoConfiguration이 등록하고, Modulith 검증 모델에만 shared를 포함한다
@SpringBootApplication
@Modulithic(additionalPackages = "com.kista.shared")
@ConfigurationPropertiesScan // AlpacaProperties 등 trading-core 소유 @ConfigurationProperties
@EnableScheduling
public class TradingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }
}
