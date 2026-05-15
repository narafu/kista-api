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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.text.ParseException;

@Configuration
public class JwtDecoderConfig {

    // 자체 발급 ES256 JWT 검증 — 모든 프로파일 단일 빈
    @Bean
    public JwtDecoder jwtDecoder(@Value("${jwt.signing-key}") String signingJwk) throws ParseException {
        ECKey ecKey = ECKey.parse(signingJwk);
        JWKSet jwkSet = new JWKSet(ecKey.toPublicJWK()); // 공개키만으로 검증
        ImmutableJWKSet<SecurityContext> jwkSource = new ImmutableJWKSet<>(jwkSet);
        ConfigurableJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, jwkSource));
        return new NimbusJwtDecoder(jwtProcessor);
    }
}
