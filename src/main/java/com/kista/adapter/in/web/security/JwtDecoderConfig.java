package com.kista.adapter.in.web.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.text.ParseException;

@Configuration
public class JwtDecoderConfig {

    // local·test 프로파일: EC 서명 키 JWK로 인-메모리 JWKSet 디코더 구성
    // dev-token과 실제 Supabase 토큰이 동일 키페어를 사용하므로 별도 JWKS 엔드포인트 불필요
    @Bean
    @Profile("local | test")
    public JwtDecoder localJwtDecoder(
            @Value("${supabase.signing-jwk}") String signingJwk) throws ParseException {
        ECKey ecKey = ECKey.parse(signingJwk);
        JWKSet jwkSet = new JWKSet(ecKey.toPublicJWK()); // 공개키만으로 검증
        ImmutableJWKSet<SecurityContext> jwkSource = new ImmutableJWKSet<>(jwkSet);
        ConfigurableJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, jwkSource));
        return new NimbusJwtDecoder(jwtProcessor);
    }

    // prod 프로파일: Supabase ECC P-256 JWKS 자동 패치·캐시·갱신
    // withJwkSetUri() 기본값은 RS256 — Supabase가 ES256 사용하므로 명시 필수
    @Bean
    @Profile("!(local | test)")
    public JwtDecoder prodJwtDecoder(@Value("${supabase.jwks-uri}") String jwksUri) {
        return NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
    }
}
