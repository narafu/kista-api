package com.kista.adapter.in.web.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtDecoderConfig {

    // local·test 프로파일: DevAuthController(local) 또는 통합테스트(test)용 HS256 검증
    @Bean
    @Profile("local | test")
    public JwtDecoder localJwtDecoder(@Value("${supabase.jwt-secret}") String secret) {
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    // prod 프로파일: Supabase ECC P-256 JWKS 자동 패치·캐시·갱신
    @Bean
    @Profile("!(local | test)")
    public JwtDecoder prodJwtDecoder(@Value("${supabase.jwks-uri}") String jwksUri) {
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }
}
