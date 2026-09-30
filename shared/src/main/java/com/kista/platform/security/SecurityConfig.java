package com.kista.platform.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins; // 쉼표 구분 허용 origin 목록 (ex: https://kista-ui.vercel.app)

    private final JwtAuthFilter jwtFilter;
    private final InternalTokenAuthFilter internalTokenFilter;

    // 규칙은 첫 매치 우선이라 순서가 인가 결과를 결정한다:
    // 공통 permitAll → /api/internal/** → /api/admin/** → 셸 기여 정책(SecurityRoutePolicy) → anyRequest().authenticated().
    // 셸 정책을 내부/관리자 규칙 뒤에 두어, 정책이 실수로 /api/admin/**·/api/internal/**을 permitAll로 뚫는 사고를 구조적으로 막는다
    // (root 셸의 기존 라우트는 어느 것도 이 두 경로와 겹치지 않아 순서 변경이 기존 인가 결과를 바꾸지 않는다)
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectProvider<SecurityRoutePolicy> policies) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/error").permitAll();
                    auth.requestMatchers("/actuator/**").permitAll(); // management port(8081) 전용, Render에서 외부 미노출
                    auth.requestMatchers("/swagger-ui/**", "/api-docs/**", "/swagger-ui.html").permitAll();
                    auth.requestMatchers("/api/internal/**").hasRole("INTERNAL");
                    auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
                    // 프로세스 고유 라우트 규칙 — 앱셸(root web)이 SecurityRoutePolicy 빈으로 기여, 없으면 빈 스트림
                    policies.orderedStream().forEach(policy -> policy.contribute(auth));
                    auth.anyRequest().authenticated();
                })
                // InternalTokenAuthFilter는 JWT 필터보다 먼저 실행 (내부 API는 JWT 불필요)
                .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter.class)
                // JWT 필터를 Spring Security 체인 내부에만 등록
                .addFilterBefore(jwtFilter, InternalTokenAuthFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                )
                .build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // JwtAuthFilter가 서블릿 필터 체인에 중복 등록되지 않도록 비활성화
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    // InternalTokenAuthFilter도 서블릿 필터 체인 중복 등록 비활성화
    @Bean
    public FilterRegistrationBean<InternalTokenAuthFilter> internalFilterRegistration(InternalTokenAuthFilter filter) {
        FilterRegistrationBean<InternalTokenAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
