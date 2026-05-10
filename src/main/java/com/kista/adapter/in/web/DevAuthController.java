package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.TokenResponse;
import com.kista.domain.model.User;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
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

    @Value("${supabase.jwt-secret}")
    private String jwtSecret;

    @Operation(summary = "[DEV] 개발용 JWT 토큰 발급 — 로컬 프로파일 전용")
    @SecurityRequirements // 자물쇠 아이콘 제거 (인증 없이 호출 가능)
    @PostMapping("/dev-token")
    public TokenResponse devToken() {
        // 테스트 유저 생성 or 기존 유저 반환 (idempotent)
        User user = registerUser.register(DEV_KAKAO_ID, "개발 테스트 유저", DEV_USER_ID);
        // ACTIVE 상태로 설정 (이미 ACTIVE여도 무해)
        approveUser.approve(user.id());

        String token = Jwts.builder()
                .subject(user.id().toString())
                .expiration(new Date(System.currentTimeMillis() + TOKEN_TTL_MS))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        return new TokenResponse(token, "bearer", TOKEN_TTL_MS / 1000);
    }
}
