package com.kista.tradingcore.web;

import com.kista.shared.platform.security.SecurityRoutePolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

// trading-core 프로세스(kista-trading) 고유 공개 라우트 규칙 — root RootSecurityPolicy와 대칭.
// platform.security.SecurityConfig가 공통 규칙(/api/internal/**, /api/admin/**) 뒤에 이 정책을 적용한다
@Component
public class TradingSecurityPolicy implements SecurityRoutePolicy {

    @Override
    public void contribute(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry.requestMatchers(HttpMethod.GET, "/api/crypto-market-indicators").permitAll(); // 비인증 대시보드용 공개 시장 지표(계좌·사용자 무관)
    }
}
