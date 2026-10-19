package com.kista.tradingcore.web;

import com.kista.shared.platform.security.SecurityRoutePolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

// trading-core 프로세스(kista-trading) 고유 공개 라우트 규칙 — root RootSecurityPolicy와 대칭.
// platform.security.SecurityConfig가 공통 규칙(/api/internal/**, /api/admin/**) 뒤에 이 정책을 적용한다
@Component
public class TradingSecurityPolicy implements SecurityRoutePolicy {

    // 비인증 대시보드용 공개 시장 지표(계좌·사용자 무관) — permitAll과 JwtAuthFilter 무효 토큰 익명 통과 판정이 공유
    static final RequestMatcher CRYPTO_MARKET_INDICATORS =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/crypto-market-indicators");

    @Override
    public void contribute(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry.requestMatchers(CRYPTO_MARKET_INDICATORS).permitAll();
    }

    @Override
    public List<RequestMatcher> anonymousFallbackMatchers() {
        return List.of(CRYPTO_MARKET_INDICATORS);
    }
}
