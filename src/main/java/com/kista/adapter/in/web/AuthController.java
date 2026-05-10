package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.UserResponse;
import com.kista.adapter.out.sse.SseEmitterRegistry;
import com.kista.domain.model.CooldownException;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegisterUserUseCase registerUser;
    private final GetUserUseCase getUser;
    private final ApproveUserUseCase approveUser;
    private final SseEmitterRegistry sseEmitterRegistry; // SSE 연결 등록

    // 카카오 콜백 요청 바디
    record KakaoCallbackRequest(String kakaoId, String nickname) {}

    // 카카오 OAuth 콜백 처리 — 신규 가입 시 PENDING 저장, 기존이면 조회
    @PostMapping("/kakao/callback")
    public UserResponse kakaoCallback(@AuthenticationPrincipal UUID userId,
                                      @RequestBody KakaoCallbackRequest request) {
        try {
            return UserResponse.from(
                    registerUser.register(request.kakaoId(), request.nickname(), userId)
            );
        } catch (DataIntegrityViolationException e) {
            // 로그인 중복 클릭 race condition: 이미 저장된 사용자 반환
            return UserResponse.from(getUser.getByKakaoId(request.kakaoId()));
        }
    }

    // 현재 사용자 정보 및 상태 조회
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UUID userId) {
        return UserResponse.from(getUser.getById(userId));
    }

    // PENDING 상태 사용자의 SSE 연결 — 승인/거절 시 브라우저 자동 리다이렉트
    @GetMapping(value = "/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statusStream(@AuthenticationPrincipal UUID userId) {
        return sseEmitterRegistry.connect(userId);
    }

    // REJECTED(24h)/PENDING(1h) 쿨다운 후 재신청
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
