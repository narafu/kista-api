package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.UserResponse;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegisterUserUseCase registerUser;
    private final GetUserUseCase getUser;
    private final ApproveUserUseCase approveUser;

    // 카카오 콜백 요청 바디
    record KakaoCallbackRequest(String kakaoId, String nickname) {}

    // 카카오 OAuth 콜백 처리 — 신규 가입 시 PENDING 저장, 기존이면 조회
    @PostMapping("/kakao/callback")
    public UserResponse kakaoCallback(@RequestBody KakaoCallbackRequest request) {
        UUID supabaseUid = extractUserId();
        return UserResponse.from(
                registerUser.register(request.kakaoId(), request.nickname(), supabaseUid)
        );
    }

    // 현재 사용자 정보 및 상태 조회
    @GetMapping("/me")
    public UserResponse me() {
        UUID userId = extractUserId();
        return UserResponse.from(getUser.getById(userId));
    }

    // 거절된 사용자 재신청 (REJECTED → PENDING)
    @PostMapping("/reapply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reapply() {
        UUID userId = extractUserId();
        try {
            approveUser.reapply(userId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private UUID extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다");
        }
        return UUID.fromString((String) auth.getPrincipal());
    }
}
