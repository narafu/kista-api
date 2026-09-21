package com.kista.web;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// JPA 엔티티·리포지토리 스캔을 root 소유 패키지로 제한 — KistaApplication.scanBasePackages는 컴포넌트 스캔만 제한하고
// 엔티티/리포지토리 스캔은 com.kista 전체(AutoConfigurationPackages)라, root 테스트 classpath의 :trading-core
// 엔티티까지 검증 대상이 되어 root baseline만 적용된 빈 DB에서 trading 테이블 누락으로 컨텍스트 로드가 실패한다.
// @SpringBootApplication에 두면 @WebMvcTest 슬라이스가 깨지므로 별도 @Configuration으로 분리(슬라이스는 스캔에서 제외).
// 패키지 목록은 KistaApplication.scanBasePackages와 동기화 — root 소유 패키지가 늘면 함께 갱신할 것.
@Configuration
@EntityScan({"com.kista.admin", "com.kista.finance", "com.kista.market", "com.kista.notify", "com.kista.stats",
        "com.kista.user", "com.kista.web", "com.kista.sharedkernel", "com.kista.platform"})
@EnableJpaRepositories({"com.kista.admin", "com.kista.finance", "com.kista.market", "com.kista.notify", "com.kista.stats",
        "com.kista.user", "com.kista.web", "com.kista.sharedkernel", "com.kista.platform"})
class RootJpaScanConfig {
}
