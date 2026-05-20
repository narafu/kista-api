package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.TokenResponse;
import com.kista.adapter.in.web.security.JwtIssuerService;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import com.kista.domain.port.out.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 로컬 개발 전용 — 운영(prod) 프로파일에서는 빈 자체가 생성되지 않음
@Tag(name = "[DEV] 개발 도구", description = "로컬 프로파일 전용 — 운영 환경에서는 노출되지 않음")
@RestController
@RequestMapping("/api/auth")
@Profile("local")
@RequiredArgsConstructor
public class DevAuthController {

    private static final UUID   DEV_USER_ID   = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID   DEV_ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String DEV_KAKAO_ID  = "dev-test-user";

    private final RegisterUserUseCase registerUser;
    private final ApproveUserUseCase  approveUser;
    private final JwtIssuerService    jwtIssuerService; // 자체 발급 ES256 JWT
    private final UserRepository      userRepository;   // ADMIN 테스트 유저 직접 저장용

    @Operation(summary = "[DEV] UID로 사용자 승인 — 로컬 프로파일 전용", description = "지정한 userId를 APPROVED 상태로 변경. Telegram 없이 로컬 승인 처리 시 사용.")
    @ApiResponse(responseCode = "200", description = "승인 성공")
    @SecurityRequirements
    @PostMapping("/dev-approve/{userId}")
    public void devApprove(
            @Parameter(description = "승인할 사용자 ID", example = "00000000-0000-0000-0000-000000000001")
            @PathVariable UUID userId) {
        approveUser.approve(userId);
    }

    @Operation(summary = "[DEV] 개발용 JWT 토큰 발급 — 로컬 프로파일 전용", description = "고정 UUID(00000000-…-0001) 테스트 유저를 자동 생성·승인 후 JWT 반환. 매번 호출해도 idempotent.")
    @ApiResponse(responseCode = "200", description = "토큰 발급 성공")
    @SecurityRequirements // 자물쇠 아이콘 제거 (인증 없이 호출 가능)
    @PostMapping("/dev-token")
    public TokenResponse devToken() {
        // 테스트 유저 생성 or 기존 유저 반환 (idempotent)
        User user = registerUser.register(DEV_KAKAO_ID, "개발 테스트 유저", DEV_USER_ID);
        // ACTIVE 상태로 설정 (이미 ACTIVE여도 무해)
        approveUser.approve(user.id());
        String token = jwtIssuerService.issue(user.id(), user.role()); // role 클레임 포함 ES256 서명
        return new TokenResponse(token, "bearer", jwtIssuerService.expiresInSeconds());
    }

    @Operation(summary = "로컬 전용 ADMIN 테스트 토큰 발급")
    @SecurityRequirements // 자물쇠 아이콘 제거 (인증 없이 호출 가능)
    @PostMapping("/dev-admin-token")
    public TokenResponse devAdminToken() {
        // 고정 ADMIN 테스트 유저 자동 생성 또는 조회 후 role promote
        User admin = userRepository.findById(DEV_ADMIN_UUID).orElseGet(() ->
                userRepository.save(new User(DEV_ADMIN_UUID, "0", "dev-admin", User.UserStatus.ACTIVE, User.UserRole.ADMIN,
                        null, null, null, null, null, null)));
        // 이미 존재하지만 ADMIN이 아닌 경우 idempotent promote
        if (admin.role() != User.UserRole.ADMIN) {
            admin = userRepository.save(new User(admin.id(), admin.kakaoId(), admin.nickname(),
                    User.UserStatus.ACTIVE, User.UserRole.ADMIN, admin.telegramBotToken(), admin.telegramChatId(),
                    admin.telegramBotUsername(), admin.createdAt(), admin.updatedAt(), admin.lastReappliedAt()));
        }
        String token = jwtIssuerService.issue(admin.id(), User.UserRole.ADMIN); // ADMIN role ES256 서명
        return new TokenResponse(token, "bearer", 604800);
    }
}
