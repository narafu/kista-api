package com.kista.user.domain.auth;


import java.util.UUID;
import com.kista.sharedkernel.UserRole;

// TokenUseCase.refresh() 반환 타입 — 컨트롤러가 AT 발급 + RT 쿠키 설정에 사용
public record TokenRefreshResult(UUID userId, UserRole userRole, String newRawRefreshToken) {}
