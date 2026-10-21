package com.kista.api.user.adapter.in.web;

import com.kista.api.user.adapter.in.web.dto.TokenResponse;
import com.kista.api.user.adapter.in.web.security.JwtIssuerService;
import com.kista.api.user.adapter.in.web.security.RefreshTokenCookieHelper;
import com.kista.api.user.domain.model.User;
import com.kista.api.user.application.usecase.TokenUseCase;
import com.kista.api.user.application.usecase.UserSettingsUseCase;
import com.kista.api.user.application.usecase.UserUseCase;
import com.kista.api.user.application.port.output.UserPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import com.kista.shared.kernel.UserRole;
import com.kista.shared.kernel.UserStatus;

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
    // E2E 전용 — dev 시드(...0001에만 적용)가 붙지 않아 "계좌 없음"에서 시작해야 하는 시나리오에 쓴다
    private static final UUID   DEV_E2E_USER_ID  = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String DEV_E2E_KAKAO_ID = "dev-e2e-user";

    private final UserUseCase              userUseCase;
    private final JwtIssuerService         jwtIssuerService; // 자체 발급 ES256 JWT
    private final UserPort                 userPort;          // ADMIN 테스트 유저 직접 저장용
    private final TokenUseCase             tokenUseCase;      // RT 발급
    private final RefreshTokenCookieHelper cookieHelper;      // RT 쿠키 설정
    private final UserSettingsUseCase      userSettingsUseCase; // trading-core 복제본 재동기화

    @Operation(summary = "[DEV] UID로 사용자 승인 — 로컬 프로파일 전용", description = "지정한 userId를 APPROVED 상태로 변경. Telegram 없이 로컬 승인 처리 시 사용.")
    @ApiResponse(responseCode = "200", description = "승인 성공")
    @SecurityRequirements
    @PostMapping("/dev-approve/{userId}")
    public void devApprove(
            @Parameter(description = "승인할 사용자 ID", example = "00000000-0000-0000-0000-000000000001")
            @PathVariable UUID userId) {
        userUseCase.approve(userId);
    }

    @Operation(summary = "[DEV] 개발용 JWT 토큰 발급 — 로컬 프로파일 전용", description = "고정 UUID(00000000-…-0001) 테스트 유저를 자동 생성·승인 후 JWT 반환. 매번 호출해도 idempotent.")
    @ApiResponse(responseCode = "200", description = "토큰 발급 성공")
    @SecurityRequirements // 자물쇠 아이콘 제거 (인증 없이 호출 가능)
    @PostMapping("/dev-token")
    public TokenResponse devToken(HttpServletRequest request, HttpServletResponse response) {
        return issueUserToken(DEV_KAKAO_ID, "개발 테스트 유저", DEV_USER_ID, request, response);
    }

    @Operation(summary = "[DEV] E2E 전용 JWT 토큰 발급 — 로컬 프로파일 전용", description = "고정 UUID(00000000-…-0003) E2E 유저를 자동 생성·승인 후 JWT 반환. dev 시드가 적용되지 않는 빈 사용자.")
    @ApiResponse(responseCode = "200", description = "토큰 발급 성공")
    @SecurityRequirements // 자물쇠 아이콘 제거 (인증 없이 호출 가능)
    @PostMapping("/dev-e2e-token")
    public TokenResponse devE2eToken(HttpServletRequest request, HttpServletResponse response) {
        return issueUserToken(DEV_E2E_KAKAO_ID, "E2E 테스트 유저", DEV_E2E_USER_ID, request, response);
    }

    @Operation(summary = "로컬 전용 ADMIN 테스트 토큰 발급")
    @SecurityRequirements // 자물쇠 아이콘 제거 (인증 없이 호출 가능)
    @PostMapping("/dev-admin-token")
    public TokenResponse devAdminToken(HttpServletRequest request, HttpServletResponse response) {
        // 고정 ADMIN 테스트 유저 자동 생성 또는 조회 후 role promote
        User admin = userPort.findById(DEV_ADMIN_UUID).orElseGet(() ->
                // PENDING으로 저장 후 아래 approve()로 ACTIVE 전환 — 복제본 동기화 이벤트 발행 경로
                userPort.save(new User(DEV_ADMIN_UUID, "0", "dev-admin", null, UserStatus.PENDING, UserRole.ADMIN,
                        null, null, null, null, null, User.DEFAULT_CHANNEL)));
        // 이미 존재하지만 ADMIN이 아닌 경우 idempotent promote
        if (admin.role() != UserRole.ADMIN) {
            admin = userPort.save(admin.withRole(UserRole.ADMIN));
        }
        activateOrResync(admin);
        return issueTokens(admin.id(), UserRole.ADMIN, request, response);
    }

    private TokenResponse issueUserToken(String kakaoId, String nickname, UUID userId,
                                         HttpServletRequest request, HttpServletResponse response) {
        // 테스트 유저 생성 or 기존 유저 반환 (idempotent)
        User user = userUseCase.register(kakaoId, nickname, userId, null);
        activateOrResync(user);
        return issueTokens(user.id(), user.role(), request, response);
    }

    // RT 발급 후 HttpOnly 쿠키 설정 + role 클레임 포함 ES256 AT 반환 (TTL은 expiresInSeconds()로 통일)
    private TokenResponse issueTokens(UUID userId, UserRole role,
                                      HttpServletRequest request, HttpServletResponse response) {
        String rawRt = tokenUseCase.issueRefreshToken(userId, request.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.issue(rawRt).toString());
        String at = jwtIssuerService.issue(userId, role);
        return new TokenResponse(at, "bearer", jwtIssuerService.expiresInSeconds());
    }

    // ACTIVE 전환(approve가 복제본 동기화 이벤트 발행) — 이미 ACTIVE면 approve()가 409라 대신 재동기화만 한다.
    // 로컬은 Redis 초기화·trading 스키마 재생성으로 trading.user_notify_profile이 유실될 수 있는데, 그대로 두면 전략 등록이 404
    private void activateOrResync(User user) {
        if (user.status() != UserStatus.ACTIVE) userUseCase.approve(user.id());
        else userSettingsUseCase.resyncNotifyProfile(user.id());
    }
}
