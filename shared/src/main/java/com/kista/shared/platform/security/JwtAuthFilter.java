package com.kista.shared.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtDecoder jwtDecoder;
    private final TokenBlacklistPort tokenBlacklistPort; // Redis 블랙리스트 체크 (포트 역전 — user/trading 각자 구현)
    private final ObjectProvider<SecurityRoutePolicy> policies; // 무효 토큰을 익명으로 통과시킬 공개 경로 공급원

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        if (token != null && !authenticate(token, request, response)) {
            return;
        }
        filterChain.doFilter(request, response); // try 밖 — 하위 체인 예외를 인증 실패(401)로 삼키지 않는다
    }

    // 토큰 검증 후 SecurityContext 설정 — 체인을 계속 진행하면 true, 401을 이미 썼으면 false.
    // 무효 토큰이라도 공개 경로(anonymousFallbackMatchers)면 컨텍스트를 비우고 익명으로 진행한다 —
    // 만료 AT 쿠키가 남은 비로그인 사용자가 공개 API에서 401(→UI 강제 로그아웃)을 받지 않게
    private boolean authenticate(String token, HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            Jwt jwt = jwtDecoder.decode(token);
            UUID userId = UUID.fromString(jwt.getSubject()); // sub 클레임 = 사용자 UUID

            // jti 블랙리스트 체크 (로그아웃된 AT) → userId 블랙리스트 체크 (탈퇴/거절)
            String jti = jwt.getId();
            if ((jti != null && tokenBlacklistPort.isJtiBlacklisted(jti)) || tokenBlacklistPort.isBlacklisted(userId)) {
                log.debug("블랙리스트 차단: userId={}, jti={}", userId, jti);
                return anonymousFallback(request) || reject(response,
                        "{\"error\":\"TOKEN_BLACKLISTED\",\"message\":\"로그아웃된 토큰입니다. 다시 로그인해 주세요.\"}");
            }

            // role 변경 이전 발급 AT 차단 — 강등/승격 즉시 반영 (UI refresh가 DB role로 새 AT 발급)
            // iat 없는 구버전 AT는 발급 시점 확인 불가 → 보수적으로 차단
            java.time.Instant roleChangedAt = tokenBlacklistPort.roleChangedAt(userId);
            if (roleChangedAt != null
                    && (jwt.getIssuedAt() == null || jwt.getIssuedAt().isBefore(roleChangedAt))) {
                log.debug("stale role AT 차단: userId={}, iat={}, roleChangedAt={}", userId, jwt.getIssuedAt(), roleChangedAt);
                return anonymousFallback(request) || reject(response,
                        "{\"error\":\"TOKEN_STALE_ROLE\",\"message\":\"권한이 변경되었습니다. 다시 로그인해 주세요.\"}");
            }

            // role claim → ROLE_* authority 변환 (claim 없으면 빈 authorities)
            String roleClaim = jwt.getClaimAsString("role");
            List<SimpleGrantedAuthority> authorities = roleClaim == null
                    ? List.of()
                    : List.of(new SimpleGrantedAuthority("ROLE_" + roleClaim));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, authorities)
            );
            return true;
        } catch (Exception e) { // JwtException + NPE + IAE 등 모두 처리
            log.warn("JWT 인증 실패: {}", e.getMessage());
            if (anonymousFallback(request)) {
                return true;
            }
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }

    // 401 + 오류 코드 body(kista-ui가 error 필드를 읽음) — 항상 false(체인 중단)
    private static boolean reject(HttpServletResponse response, String body) throws IOException {
        // ASYNC(SSE 타임아웃) 재디스패치 시 응답이 이미 커밋돼 있으면 getWriter() 호출이 IllegalStateException을 던짐
        if (!response.isCommitted()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(body);
        }
        return false;
    }

    // 공개 경로면 컨텍스트를 비우고 true — OrRequestMatcher는 빈 목록에 IAE라(정책 없는 슬라이스 테스트) stream으로 판정
    private boolean anonymousFallback(HttpServletRequest request) {
        boolean fallback = policies.orderedStream()
                .flatMap(policy -> policy.anonymousFallbackMatchers().stream())
                .anyMatch(matcher -> matcher.matches(request));
        if (fallback) {
            SecurityContextHolder.clearContext();
        }
        return fallback;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // ASYNC(타임아웃) 재처리 시에도 재인증 — 기본값(true)이면 SSE 타임아웃마다 SecurityContext가 비어 AuthorizationFilter가 접근을 거부함
        return false;
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
