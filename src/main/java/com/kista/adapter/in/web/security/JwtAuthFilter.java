package com.kista.adapter.in.web.security;

import com.kista.domain.port.in.BlacklistUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final BlacklistUseCase blacklistUseCase; // Redis 블랙리스트 체크 (adapter.in → domain.port.in)

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        if (token != null) {
            try {
                Jwt jwt = jwtDecoder.decode(token);
                UUID userId = UUID.fromString(jwt.getSubject()); // sub 클레임 = 사용자 UUID

                // jti 블랙리스트 체크 (로그아웃된 AT) → userId 블랙리스트 체크 (탈퇴/거절)
                String jti = jwt.getId();
                if ((jti != null && blacklistUseCase.isJtiBlacklisted(jti)) || blacklistUseCase.isBlacklisted(userId)) {
                    log.debug("블랙리스트 차단: userId={}, jti={}", userId, jti);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"TOKEN_BLACKLISTED\",\"message\":\"로그아웃된 토큰입니다. 다시 로그인해 주세요.\"}");
                    return;
                }

                // role 변경 이전 발급 AT 차단 — 강등/승격 즉시 반영 (UI refresh가 DB role로 새 AT 발급)
                // iat 없는 구버전 AT는 발급 시점 확인 불가 → 보수적으로 차단
                java.time.Instant roleChangedAt = blacklistUseCase.roleChangedAt(userId);
                if (roleChangedAt != null
                        && (jwt.getIssuedAt() == null || jwt.getIssuedAt().isBefore(roleChangedAt))) {
                    log.debug("stale role AT 차단: userId={}, iat={}, roleChangedAt={}", userId, jwt.getIssuedAt(), roleChangedAt);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"TOKEN_STALE_ROLE\",\"message\":\"권한이 변경되었습니다. 다시 로그인해 주세요.\"}");
                    return;
                }

                // role claim → ROLE_* authority 변환 (claim 없으면 빈 authorities)
                String roleClaim = jwt.getClaimAsString("role");
                List<SimpleGrantedAuthority> authorities = roleClaim == null
                        ? List.of()
                        : List.of(new SimpleGrantedAuthority("ROLE_" + roleClaim));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userId, null, authorities)
                );
            } catch (Exception e) { // JwtException + NPE + IAE 등 모두 처리
                log.warn("JWT 인증 실패: {}", e.getMessage());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
