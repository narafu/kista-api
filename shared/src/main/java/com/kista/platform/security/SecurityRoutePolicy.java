package com.kista.platform.security;

import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

// 프로세스 고유 라우트 인가 규칙(permitAll/authenticated)을 앱셸이 SecurityConfig에 기여하는 확장점 —
// 인프라 leaf(platform.security)가 특정 프로세스에만 있는 경로를 알지 않도록 한다.
// 기여 규칙은 공통 규칙(/api/internal/**, /api/admin/**) 뒤·anyRequest().authenticated() 앞에 적용된다.
@FunctionalInterface
public interface SecurityRoutePolicy {

    // 셸 고유 경로 규칙을 registry에 추가 — 같은 정책 안에서는 선언 순서가 첫 매치 우선순위다
    void contribute(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry);
}
