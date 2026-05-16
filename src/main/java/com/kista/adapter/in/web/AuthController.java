package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.KakaoLoginResponse;
import com.kista.adapter.in.web.dto.UserResponse;
import com.kista.adapter.in.web.security.JwtIssuerService;
import com.kista.adapter.out.sse.SseEmitterRegistry;
import com.kista.domain.model.CooldownException;
import com.kista.domain.model.User;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.KakaoLoginUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Tag(name = "인증", description = "카카오 OAuth 로그인, 사용자 정보 조회, 승인 신청")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final KakaoLoginUseCase kakaoLoginUseCase;
    private final JwtIssuerService jwtIssuerService;
    private final GetUserUseCase getUser;
    private final ApproveUserUseCase approveUser;
    private final SseEmitterRegistry sseEmitterRegistry; // SSE 연결 등록

    record KakaoCallbackRequest(
            @Schema(description = "카카오 OAuth 인가 코드", example = "xxxxxxxxxxxxxxxxxxxxxxxx")
            String code,
            @Schema(description = "카카오 리다이렉트 URI", example = "https://example.com/oauth/kakao")
            String redirectUri
    ) {} // 카카오 인가 코드 + 리다이렉트 URI

    // 카카오 OAuth 인가 코드로 로그인 처리 — 신규 가입 or 기존 유저 조회 후 JWT 발급
    @Operation(summary = "카카오 로그인", description = "카카오 OAuth 인가 코드로 로그인. 신규 사용자는 PENDING 상태로 가입 후 관리자 승인 대기.")
    @ApiResponse(responseCode = "200", description = "로그인 성공 (JWT 반환)")
    @PostMapping("/kakao/callback")
    @SecurityRequirements
    public KakaoLoginResponse kakaoCallback(@RequestBody KakaoCallbackRequest request) {
        User user = kakaoLoginUseCase.login(request.code(), request.redirectUri());
        String token = jwtIssuerService.issue(user.id());
        return new KakaoLoginResponse(token, "bearer", jwtIssuerService.expiresInSeconds(), UserResponse.from(user));
    }

    // 현재 사용자 정보 및 상태 조회
    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자의 프로필 및 계정 상태 반환.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UUID userId) {
        return UserResponse.from(getUser.getById(userId));
    }

    // PENDING 상태 사용자의 SSE 연결 — 승인/거절 시 브라우저 자동 리다이렉트
    @Operation(summary = "승인 상태 SSE 스트림", description = "PENDING 상태 사용자가 연결. 관리자 승인/거절 시 이벤트 수신 후 브라우저 자동 이동.")
    @ApiResponse(responseCode = "200", description = "SSE 스트림 연결 성공")
    @GetMapping(value = "/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statusStream(@AuthenticationPrincipal UUID userId) {
        return sseEmitterRegistry.connect(userId);
    }

    // REJECTED(24h)/PENDING(1h) 쿨다운 후 재신청
    @Operation(summary = "승인 재신청", description = "거절(24시간) 또는 대기(1시간) 쿨다운 이후 재신청 가능.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "재신청 성공"),
            @ApiResponse(responseCode = "400", description = "재신청 불가 상태"),
            @ApiResponse(responseCode = "429", description = "쿨다운 중 — 응답 body에 재신청 가능 시각(ISO-8601) 포함")
    })
    @PostMapping("/reapply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reapply(@AuthenticationPrincipal UUID userId) {
        try {
            approveUser.reapply(userId);
        } catch (CooldownException e) {
            // 쿨다운 중 — 재신청 가능 시각을 body에 포함
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getRetryAfter().toString());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
