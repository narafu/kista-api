package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.TokenResponse;
import com.kista.adapter.in.web.security.JwtIssuerService;
import com.kista.domain.model.User;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 로컬 개발 전용 — 운영(prod) 프로파일에서는 빈 자체가 생성되지 않음
@RestController
@RequestMapping("/api/auth")
@Profile("local")
@RequiredArgsConstructor
public class DevAuthController {

    private static final UUID   DEV_USER_ID  = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String DEV_KAKAO_ID = "dev-test-user";

    private final RegisterUserUseCase registerUser;
    private final ApproveUserUseCase  approveUser;
    private final JwtIssuerService    jwtIssuerService; // 자체 발급 ES256 JWT

    @Operation(summary = "[DEV] 개발용 JWT 토큰 발급 — 로컬 프로파일 전용")
    @SecurityRequirements // 자물쇠 아이콘 제거 (인증 없이 호출 가능)
    @PostMapping("/dev-token")
    public TokenResponse devToken() {
        // 테스트 유저 생성 or 기존 유저 반환 (idempotent)
        User user = registerUser.register(DEV_KAKAO_ID, "개발 테스트 유저", DEV_USER_ID);
        // ACTIVE 상태로 설정 (이미 ACTIVE여도 무해)
        approveUser.approve(user.id());
        String token = jwtIssuerService.issue(user.id()); // JwtIssuerService로 ES256 서명
        return new TokenResponse(token, "bearer", jwtIssuerService.expiresInSeconds());
    }
}
