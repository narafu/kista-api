package com.kista.adapter.in.web.dto;

import com.kista.domain.model.user.User;

// 관리자 역할 변경 요청 body
public record AdminRoleRequest(User.UserRole role) {}
