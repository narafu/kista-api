package com.kista.adapter.in.web.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/**").permitAll() // management port(8081) 전용, Render에서 외부 미노출
                        .requestMatchers("/swagger-ui/**", "/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/telegram/webhook").permitAll()
                        .requestMatchers("/api/auth/status-stream").authenticated() // 상태 SSE 연결은 인증 필수
                        .requestMatchers("/api/trades/stream").authenticated() // 매매 SSE 연결은 인증 필수
                        .requestMatchers(HttpMethod.DELETE, "/api/auth/me").authenticated() // 회원 탈퇴는 인증 필수
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/market/**").permitAll() // 비인증 대시보드용 공개 엔드포인트
                        .requestMatchers(HttpMethod.GET, "/api/meta").permitAll() // enum SSOT — 레이아웃 로드 시 인증 불필요
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/internal/**").hasRole("INTERNAL")
                        .anyRequest().authenticated()
                )
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
