package com.kista.tradingcore.support;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// trading-core 모든 모듈의 @DataJpaTest용 설정(com.kista.tradingcore.trading 포함) — 상위 패키지에 @SpringBootConfiguration이
// 없어(TradingApplication은 앱셸 com.kista.tradingcore.web에 있고 루트 KistaApplication은 클래스패스에 없음)
// @ContextConfiguration으로 명시해야 한다. 스캔 범위는 TradingApplication/JpaRepositoryConfig와 동일
@Configuration
@EntityScan(basePackages = {"com.kista.tradingcore.trading", "com.kista.tradingcore.broker", "com.kista.tradingcore.account", "com.kista.tradingcore.privacy", "com.kista.tradingcore.marketcalendar"})
@EnableJpaRepositories(basePackages = {"com.kista.tradingcore.trading", "com.kista.tradingcore.broker", "com.kista.tradingcore.account", "com.kista.tradingcore.privacy", "com.kista.tradingcore.marketcalendar"})
public class TradingCoreJpaTestConfig {
}
