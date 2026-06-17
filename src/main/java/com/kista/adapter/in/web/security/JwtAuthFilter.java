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

                // 블랙리스트 체크 — 탈퇴·로그아웃·거절된 userId 즉시 차단
                if (blacklistUseCase.isBlacklisted(userId)) {
                    log.debug("블랙리스트 차단: userId={}", userId);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
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
