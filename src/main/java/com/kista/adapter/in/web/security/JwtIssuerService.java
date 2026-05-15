package com.kista.adapter.in.web.security;

import com.nimbusds.jose.jwk.ECKey;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.interfaces.ECPrivateKey;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtIssuerService {

    public static final long TOKEN_TTL_MS = 604_800_000L; // 7일 (밀리초)

    @Value("${jwt.signing-key}")
    private String signingJwk; // EC JWK JSON 문자열

    // userId를 subject로 담은 ES256 서명 JWT 발급
    public String issue(UUID userId) {
        ECPrivateKey privateKey = parsePrivateKey();
        return Jwts.builder()
                .subject(userId.toString())
                .expiration(new Date(System.currentTimeMillis() + TOKEN_TTL_MS))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    // 토큰 유효 기간 (초 단위)
    public long expiresInSeconds() {
        return TOKEN_TTL_MS / 1000;
    }

    // JWK JSON 문자열에서 EC 개인키 파싱
    private ECPrivateKey parsePrivateKey() {
        try {
            return ECKey.parse(signingJwk).toECPrivateKey();
        } catch (Exception e) {
            throw new IllegalStateException("EC 서명 키 파싱 실패", e);
        }
    }
}
