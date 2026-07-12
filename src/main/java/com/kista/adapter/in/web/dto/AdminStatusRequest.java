package com.kista.adapter.in.web.dto;

import com.kista.domain.model.user.User;
import jakarta.validation.constraints.Size;

// 관리자 사용자 상태 변경 요청 body — ACTIVE(승인) / REJECTED(거절)
// reason은 REJECTED일 때만 사용, ACTIVE면 무시
public record AdminStatusRequest(User.UserStatus status, @Size(max = 500) String reason) {}
