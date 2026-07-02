package com.kista.adapter.in.web.dto;

import com.kista.domain.model.user.User;

// 관리자 사용자 상태 변경 요청 body — ACTIVE(승인) / REJECTED(거절)
public record AdminStatusRequest(User.UserStatus status) {}
