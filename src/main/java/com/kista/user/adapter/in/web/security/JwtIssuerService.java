package com.kista.user.adapter.in.web.security;

import com.kista.user.domain.auth.TokenConstants;
import com.kista.sharedkernel.UserRole;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
public class JwtIssuerService {

    // ES256 고정 헤더 — 서명 키는 매 발급 시 signingJwk로부터 파싱(JwtDecoderConfig와 동일한 EC 키 소스)
    private static final JwsHeader ES256_HEADER = JwsHeader.with(SignatureAlgorithm.ES256).build();

    @Value("${jwt.signing-key}")
    private String signingJwk; // EC JWK JSON 문자열

    // userId를 subject, role을 클레임으로 담은 ES256 서명 JWT 발급
    public String issue(UUID userId, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString()) // jti — 로그아웃 시 단일 AT 차단용
                .subject(userId.toString())
                .claim("role", role.name()) // 역할(USER/ADMIN) 클레임 — JwtAuthFilter에서 권한 추출
                .issuedAt(now) // iat — role 변경 시각과 비교해 stale AT 판정 (JwtAuthFilter)
                .expiresAt(now.plus(TokenConstants.AT_TTL))
                .build();
        Jwt jwt = buildEncoder().encode(JwtEncoderParameters.from(ES256_HEADER, claims));
        return jwt.getTokenValue();
    }

    // 토큰 유효 기간 (초 단위)
    public long expiresInSeconds() {
        return TokenConstants.AT_TTL.toSeconds();
    }

    // signingJwk(개인키 포함 EC JWK)로 ES256 서명용 NimbusJwtEncoder 생성 — JwtDecoderConfig와 동일한 키 소스
    private NimbusJwtEncoder buildEncoder() {
        try {
            ECKey ecKey = ECKey.parse(signingJwk);
            // kid 헤더 제거 — 기존 io.jsonwebtoken 발급 토큰은 kid 헤더가 없었으므로 헤더 shape 동일하게 유지
            ECKey signingKey = new ECKey.Builder(ecKey).keyID(null).build();
            ImmutableJWKSet<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(signingKey));
            return new NimbusJwtEncoder(jwkSource);
        } catch (Exception e) {
            throw new IllegalStateException("EC 서명 키 파싱 실패", e);
        }
    }
}
