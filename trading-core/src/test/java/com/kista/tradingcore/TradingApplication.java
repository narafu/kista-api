package com.kista.tradingcore;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

// trading-core 테스트 전용 기동 클래스 — 운영 진입점은 ApiApplication. 슬라이스·@SpringBootTest가 상향 탐색으로 찾는다
@SpringBootApplication
@Modulithic(additionalPackages = "com.kista.shared")
@ConfigurationPropertiesScan // trading-core 소유 @ConfigurationProperties
@EnableScheduling
public class TradingApplication {
}
