package com.kista.domain.model.auth;

import com.kista.domain.model.user.User;

import java.util.UUID;

// TokenUseCase.refresh() 반환 타입 — 컨트롤러가 AT 발급 + RT 쿠키 설정에 사용
public record TokenRefreshResult(UUID userId, User.UserRole userRole, String newRawRefreshToken) {}
