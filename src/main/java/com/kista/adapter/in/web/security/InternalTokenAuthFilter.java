package com.kista.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class InternalTokenAuthFilter extends OncePerRequestFilter {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String INTERNAL_PATH_PREFIX = "/api/internal/";

    private final String internalApiToken;

    public InternalTokenAuthFilter(
            @Value("${internal.api.token:}") String internalApiToken) {
        this.internalApiToken = internalApiToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // /api/internal/** 경로에만 적용
        return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (internalApiToken.isBlank() || !internalApiToken.equals(token)) {
            log.warn("내부 API 인증 실패: uri={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("INTERNAL", null,
                        List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")))
        );
        filterChain.doFilter(request, response);
    }
}
