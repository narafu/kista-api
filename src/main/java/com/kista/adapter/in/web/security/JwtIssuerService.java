package com.kista.adapter.in.web.security;

import com.kista.domain.model.auth.TokenConstants;
import com.kista.domain.model.user.User;
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

    @Value("${jwt.signing-key}")
    private String signingJwk; // EC JWK JSON 문자열

    // userId를 subject, role을 클레임으로 담은 ES256 서명 JWT 발급
    public String issue(UUID userId, User.UserRole role) {
        ECPrivateKey privateKey = parsePrivateKey();
        return Jwts.builder()
                .id(UUID.randomUUID().toString()) // jti — 로그아웃 시 단일 AT 차단용
                .subject(userId.toString())
                .claim("role", role.name()) // 역할(USER/ADMIN) 클레임 — JwtAuthFilter에서 권한 추출
                .expiration(new Date(System.currentTimeMillis() + TokenConstants.AT_TTL.toMillis()))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    // 토큰 유효 기간 (초 단위)
    public long expiresInSeconds() {
        return TokenConstants.AT_TTL.toSeconds();
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
