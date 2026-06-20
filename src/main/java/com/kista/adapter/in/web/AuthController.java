package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.KakaoLoginResponse;
import com.kista.adapter.in.web.dto.RefreshResponse;
import com.kista.adapter.in.web.dto.UserResponse;
import com.kista.adapter.in.web.security.JwtIssuerService;
import com.kista.adapter.in.web.security.RefreshTokenCookieHelper;
import com.kista.adapter.out.sse.SseEmitterRegistry;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.in.UserUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Tag(name = "인증", description = "카카오 OAuth 로그인, 토큰 갱신, 사용자 정보 조회, 승인 신청")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserUseCase userUseCase;
    private final TokenUseCase tokenUseCase;
    private final JwtIssuerService jwtIssuerService;
    private final RefreshTokenCookieHelper cookieHelper;
    private final SseEmitterRegistry sseEmitterRegistry; // SSE 연결 등록

    record KakaoCallbackRequest(
            @Schema(description = "카카오 OAuth 인가 코드", example = "xxxxxxxxxxxxxxxxxxxxxxxx")
            String code,
            @Schema(description = "카카오 리다이렉트 URI", example = "https://example.com/oauth/kakao")
            String redirectUri
    ) {} // 카카오 인가 코드 + 리다이렉트 URI

    // 카카오 OAuth 인가 코드로 로그인 — AT(body) + RT(HttpOnly 쿠키) 발급
    @Operation(summary = "카카오 로그인", description = "카카오 OAuth 인가 코드로 로그인. 신규 사용자는 PENDING 상태로 가입 후 관리자 승인 대기.")
    @ApiResponse(responseCode = "200", description = "로그인 성공 (JWT 반환)")
    @PostMapping("/kakao/callback")
    @SecurityRequirements
    public KakaoLoginResponse kakaoCallback(@RequestBody KakaoCallbackRequest request,
                                             HttpServletRequest httpRequest,
                                             HttpServletResponse httpResponse) {
        User user = userUseCase.login(request.code(), request.redirectUri());
        String rawRt = tokenUseCase.issueRefreshToken(user.id(), httpRequest.getHeader("User-Agent"));
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.issue(rawRt).toString());
        String at = jwtIssuerService.issue(user.id(), user.role());
        // rawRt를 body에도 포함 — Next.js Route Handler가 Edge Runtime Set-Cookie 필터링을 우회해 HttpOnly 쿠키로 변환
        return new KakaoLoginResponse(at, "bearer", jwtIssuerService.expiresInSeconds(), UserResponse.from(user), rawRt);
    }

    // RT 쿠키로 새 AT + RT 발급 (RTR)
    @Operation(summary = "토큰 갱신", description = "HttpOnly 쿠키의 refresh_token으로 새 AT + RT를 발급합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "갱신 성공"),
            @ApiResponse(responseCode = "401", description = "유효하지 않은 refresh token")
    })
    @PostMapping("/refresh")
    @SecurityRequirements
    public RefreshResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawRt = cookieHelper.extract(request);
        if (rawRt == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        var result = tokenUseCase.refresh(rawRt, request.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.issue(result.newRawRefreshToken()).toString());
        String newAt = jwtIssuerService.issue(result.userId(), result.userRole());
        // rawRefreshToken을 body에 포함 — proxy.ts가 Edge Runtime에서 Set-Cookie 헤더 필터링을 우회하는 데 사용
        return new RefreshResponse(newAt, "bearer", jwtIssuerService.expiresInSeconds(), result.newRawRefreshToken());
    }

    // 로그아웃 — RT 삭제 + userId 블랙리스트 + 쿠키 삭제
    @Operation(summary = "로그아웃", description = "refresh_token 쿠키를 무효화하고 AT를 즉시 차단합니다.")
    @ApiResponse(responseCode = "204", description = "로그아웃 성공")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirements
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String rawRt = cookieHelper.extract(request);
        if (rawRt != null) {
            tokenUseCase.logout(rawRt);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.clear().toString());
    }

    // 현재 사용자 정보 및 상태 조회
    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자의 프로필 및 계정 상태 반환.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UUID userId) {
        return UserResponse.from(userUseCase.getById(userId));
    }

    // PENDING 상태 사용자의 SSE 연결 — 승인/거절 시 브라우저 자동 리다이렉트
    @Operation(summary = "승인 상태 SSE 스트림", description = "PENDING 상태 사용자가 연결. 관리자 승인/거절 시 이벤트 수신 후 브라우저 자동 이동.")
    @ApiResponse(responseCode = "200", description = "SSE 스트림 연결 성공")
    @GetMapping(value = "/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statusStream(@AuthenticationPrincipal UUID userId) {
        return sseEmitterRegistry.connect(userId);
    }

    // REJECTED(24h)/PENDING(1h) 쿨다운 후 재신청 — CooldownException→429, IllegalStateException→400은 GlobalExceptionHandler 처리
    @Operation(summary = "승인 재신청", description = "거절(24시간) 또는 대기(1시간) 쿨다운 이후 재신청 가능.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "재신청 성공"),
            @ApiResponse(responseCode = "400", description = "재신청 불가 상태"),
            @ApiResponse(responseCode = "429", description = "쿨다운 중 — 응답 body에 재신청 가능 시각(ISO-8601) 포함")
    })
    @PostMapping("/approval-requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestApproval(@AuthenticationPrincipal UUID userId) {
        userUseCase.reapply(userId);
    }

    // 회원 탈퇴 — cascade로 계좌/거래내역/토큰 자동 삭제 (FK CASCADE + 블랙리스트 등재)
    @Operation(summary = "회원 탈퇴", description = "본인 계정 및 모든 연관 데이터(계좌, 거래내역 등)를 즉시 삭제합니다.")
    @ApiResponse(responseCode = "204", description = "탈퇴 성공")
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal UUID userId, HttpServletResponse response) {
        userUseCase.deleteMe(userId);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.clear().toString());
    }
}
