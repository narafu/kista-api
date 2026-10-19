package com.kista.api.web;

import com.kista.shared.platform.security.SecurityRoutePolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

// root 프로세스(kista-api·kista-scheduler) 고유 공개/인증 라우트 규칙 — platform.security.SecurityConfig가
// 공통 규칙(/api/internal/**, /api/admin/**) 뒤에 이 정책을 적용한다. 규칙 순서는 첫 매치 우선이라
// 구체 경로(status-stream·me·approval-requests)를 /api/auth/** permitAll보다 먼저 둔다.
@Component
public class RootSecurityPolicy implements SecurityRoutePolicy {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();
    // 공개 읽기 경로 — permitAll 등록과 JwtAuthFilter 무효 토큰 익명 통과 판정이 같은 인스턴스를 쓴다
    static final RequestMatcher CLIENT_ERRORS = PATHS.matcher(HttpMethod.POST, "/api/client-errors"); // UI 오류 리포트 — 로그인 전 화면도 호출 가능해야 함
    static final RequestMatcher MARKET = PATHS.matcher(HttpMethod.GET, "/api/market/**"); // 비인증 대시보드용 공개 엔드포인트
    static final RequestMatcher META = PATHS.matcher(HttpMethod.GET, "/api/meta"); // enum SSOT — 레이아웃 로드 시 인증 불필요
    static final RequestMatcher RUNTIME_CONFIG = PATHS.matcher(HttpMethod.GET, "/api/runtime-config"); // 동적 가입·생성 설정 — 로그인 전 조회 허용

    @Override
    public void contribute(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        registry.requestMatchers("/telegram/webhook").permitAll();
        registry.requestMatchers(CLIENT_ERRORS).permitAll();
        registry.requestMatchers("/api/auth/status-stream").authenticated(); // 상태 SSE 연결은 인증 필수
        registry.requestMatchers("/api/trades/stream").authenticated(); // 매매 SSE 연결은 인증 필수
        registry.requestMatchers(HttpMethod.DELETE, "/api/auth/me").authenticated(); // 회원 탈퇴는 인증 필수
        registry.requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated(); // 내 정보 — principal 필수(없으면 getById(null) 500)
        registry.requestMatchers(HttpMethod.POST, "/api/auth/approval-requests").authenticated(); // 승인 재신청 — principal 필수
        registry.requestMatchers("/api/auth/**").permitAll();
        registry.requestMatchers(MARKET, META, RUNTIME_CONFIG).permitAll();
    }

    // /api/auth/**는 permitAll이지만 /me 등이 principal을 읽어 넣지 않는다 — 무효 토큰은 계속 401(TOKEN_* body)
    @Override
    public List<RequestMatcher> anonymousFallbackMatchers() {
        return List.of(CLIENT_ERRORS, MARKET, META, RUNTIME_CONFIG);
    }
}
