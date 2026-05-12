package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.TokenResponse;
import com.kista.domain.model.User;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import com.nimbusds.jose.jwk.ECKey;
import io.jsonwebtoken.Jwts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.ECPrivateKey;
import java.util.Date;
import java.util.UUID;

// 로컬 개발 전용 — 운영(prod) 프로파일에서는 빈 자체가 생성되지 않음
@RestController
@RequestMapping("/api/auth")
@Profile("local")
@RequiredArgsConstructor
public class DevAuthController {

    private static final UUID   DEV_USER_ID  = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String DEV_KAKAO_ID = "dev-test-user";
    private static final long   TOKEN_TTL_MS = 86_400_000L; // 24h

    private final RegisterUserUseCase registerUser;
    private final ApproveUserUseCase  approveUser;

    // 로컬 Supabase EC 서명 키 — JwtDecoderConfig와 동일 키페어, JWKS 공개키로 검증 가능
    @Value("${supabase.signing-jwk}")
    private String signingJwk;

    @Operation(summary = "[DEV] 개발용 JWT 토큰 발급 — 로컬 프로파일 전용")
    @SecurityRequirements // 자물쇠 아이콘 제거 (인증 없이 호출 가능)
    @PostMapping("/dev-token")
    public TokenResponse devToken() {
        // 테스트 유저 생성 or 기존 유저 반환 (idempotent)
        User user = registerUser.register(DEV_KAKAO_ID, "개발 테스트 유저", DEV_USER_ID);
        // ACTIVE 상태로 설정 (이미 ACTIVE여도 무해)
        approveUser.approve(user.id());

        // EC 개인키로 ES256 서명 — 로컬 Supabase와 동일 키페어이므로 인-메모리 JwtDecoder로 검증됨
        ECPrivateKey privateKey;
        try {
            privateKey = ECKey.parse(signingJwk).toECPrivateKey();
        } catch (Exception e) {
            throw new IllegalStateException("EC 서명 키 파싱 실패", e);
        }

        String token = Jwts.builder()
                .subject(user.id().toString())
                .expiration(new Date(System.currentTimeMillis() + TOKEN_TTL_MS))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();

        return new TokenResponse(token, "bearer", TOKEN_TTL_MS / 1000);
    }
}
