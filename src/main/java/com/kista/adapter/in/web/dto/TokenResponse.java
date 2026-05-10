package com.kista.adapter.in.web.dto;

// Swagger dev-token 엔드포인트 응답 DTO
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
