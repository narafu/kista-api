package com.kista.tradingcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// kista-trading 전체 부팅 스모크 — trading-core에는 슬라이스 테스트만 있어 JPA 리포지토리 미등록 같은 부팅 결함을
// 로컬 2-프로세스 부팅 전까지 못 잡았다(미등록 JPA 리포지토리 가드). 스캔 설정 변경의 회귀 가드
@SpringBootTest(properties = {
        "spring.data.redis.url=redis://localhost:6379" // trading-core application.yml은 REDIS_URL 기본값이 없다(필수)
})
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB 연결 컨텍스트 로드
class TradingApplicationContextLoadTest {

    private static final int TRADING_JPA_REPOSITORIES = 18; // 실측값(grep·테스트 일치) — DCA 설정·조건·회차 리포지토리 3개 포함

    @Autowired
    private ApplicationContext context; // 컨텍스트 로드 성공 자체가 1차 검증

    @Test
    void 트레이딩_컨텍스트는_JPA_리포지토리_전부와_보안_필터체인_하나를_갖는다() {
        assertThat(context.getBeanNamesForType(JpaRepository.class)).hasSize(TRADING_JPA_REPOSITORIES);
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
    }
}
