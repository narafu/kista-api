package com.kista.trading;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// @EnableJpaRepositories 명시 필수 — Spring Data Redis도 클래스패스에 있어(RedisBlacklistAdapter
// 용도) Boot가 "Multiple Spring Data modules found, entering strict repository configuration mode"로
// 들어가는데, strict 모드에서도 JpaRepositoriesAutoConfiguration 자체가 자동 활성화되지 않아(실측:
// scanBasePackages를 아무리 넓혀도 JPA 리포지토리 스캔 로그 자체가 안 뜨고 Redis 후보 판정만 실행됨)
// 명시 선언 없이는 *JpaRepository 빈이 하나도 등록되지 않는다 — 전체 부트를 도는 @SpringBootTest가
// trading-core 테스트 스위트에 하나도 없어(전부 @WebMvcTest/@DataJpaTest 슬라이스) 4a Task 10
// 로컬 2-프로세스 스모크 테스트 전까지 발견되지 않았던 결함.
// TradingApplication이 아닌 별도 클래스에 두는 이유는 TradingApplication.java 주석 참고 —
// @WebMvcTest 슬라이스 회귀 방지가 목적
@Configuration
@EnableJpaRepositories(basePackages = {
        "com.kista.trading",
        "com.kista.broker",
        "com.kista.account",
        "com.kista.privacy",
        "com.kista.marketcalendar",
})
class JpaRepositoryConfig {
}
