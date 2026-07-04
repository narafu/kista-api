package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 카카오 OAuth 인가 코드 + 리다이렉트 URI
public record KakaoCallbackRequest(
        @Schema(description = "카카오 OAuth 인가 코드", example = "xxxxxxxxxxxxxxxxxxxxxxxx")
        String code,
        @Schema(description = "카카오 리다이렉트 URI", example = "https://example.com/oauth/kakao")
        String redirectUri
) {}
