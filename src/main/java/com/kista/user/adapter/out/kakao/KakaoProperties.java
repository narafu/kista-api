package com.kista.user.adapter.out.kakao;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// clientId 누락·빈값이면 기동 실패 — 카카오 로그인 불능을 배포 헬스 게이트에서 잡는다. clientSecret은 선택
@Validated
@ConfigurationProperties(prefix = "kakao")
public record KakaoProperties(@NotBlank String clientId, String clientSecret) {}
