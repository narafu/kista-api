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
// 공통 규칙(/api/internal/**, /api/admin/**) 뒤에 이 정책을 적용한다. 여기에는 공개 경로만 적는다 —
// 적지 않은 경로는 SecurityConfig의 anyRequest().authenticated()가 받으므로 규칙 순서에 기대지 않고,
// /api/auth 아래에 새 핸들러가 생겨도 기본이 인증 필요다.
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
        // 토큰 없이 불러야 하는 인증 경로 — 로그인·갱신(RT 쿠키)·로그아웃
        registry.requestMatchers(HttpMethod.POST, "/api/auth/kakao/callback", "/api/auth/refresh", "/api/auth/logout").permitAll();
        // DevAuthController(local 프로파일 전용) — 그 밖의 프로파일에는 핸들러가 없어 404
        registry.requestMatchers(HttpMethod.POST, "/api/auth/dev-*", "/api/auth/dev-approve/*").permitAll();
        registry.requestMatchers(MARKET, META, RUNTIME_CONFIG).permitAll();
    }

    // 공개 인증 경로(로그인·갱신·로그아웃)는 넣지 않는다 — 무효 토큰은 계속 401(TOKEN_* body)
    @Override
    public List<RequestMatcher> anonymousFallbackMatchers() {
        return List.of(CLIENT_ERRORS, MARKET, META, RUNTIME_CONFIG);
    }
}
