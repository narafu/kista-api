package com.kista.web;

import com.kista.platform.security.SecurityRoutePolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

// root 프로세스(kista-api·kista-scheduler) 고유 공개/인증 라우트 규칙 — platform.security.SecurityConfig가
// 공통 규칙(/api/internal/**, /api/admin/**) 뒤에 이 정책을 적용한다. 규칙 순서는 첫 매치 우선이라
// 구체 경로(status-stream·DELETE me)를 /api/auth/** permitAll보다 먼저 둔다.
@Component
public class RootSecurityPolicy implements SecurityRoutePolicy {

    @Override
    public void contribute(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry.requestMatchers("/telegram/webhook").permitAll();
        registry.requestMatchers(HttpMethod.POST, "/api/client-errors").permitAll(); // UI 오류 리포트 — 로그인 전 화면도 호출 가능해야 함
        registry.requestMatchers("/api/auth/status-stream").authenticated(); // 상태 SSE 연결은 인증 필수
        registry.requestMatchers("/api/trades/stream").authenticated(); // 매매 SSE 연결은 인증 필수
        registry.requestMatchers(HttpMethod.DELETE, "/api/auth/me").authenticated(); // 회원 탈퇴는 인증 필수
        registry.requestMatchers("/api/auth/**").permitAll();
        registry.requestMatchers(HttpMethod.GET, "/api/market/**").permitAll(); // 비인증 대시보드용 공개 엔드포인트
        registry.requestMatchers(HttpMethod.GET, "/api/meta").permitAll(); // enum SSOT — 레이아웃 로드 시 인증 불필요
        registry.requestMatchers(HttpMethod.GET, "/api/runtime-config").permitAll(); // 동적 가입·생성 설정 — 로그인 전 조회 허용
    }
}
