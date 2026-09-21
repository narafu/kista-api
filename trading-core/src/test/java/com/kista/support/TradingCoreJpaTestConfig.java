package com.kista.support;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// com.kista.trading 밖 패키지(broker/marketcalendar 등)의 @DataJpaTest용 설정 — 상위 패키지에 @SpringBootConfiguration이
// 없어(루트 KistaApplication은 더 이상 클래스패스에 없음) @ContextConfiguration으로 명시해야 한다.
// 스캔 범위는 TradingApplication/JpaRepositoryConfig와 동일
@Configuration
@EntityScan(basePackages = {"com.kista.trading", "com.kista.broker", "com.kista.account", "com.kista.privacy", "com.kista.marketcalendar"})
@EnableJpaRepositories(basePackages = {"com.kista.trading", "com.kista.broker", "com.kista.account", "com.kista.privacy", "com.kista.marketcalendar"})
public class TradingCoreJpaTestConfig {
}
