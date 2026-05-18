package com.kista.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

// admin.kakao-ids 환경변수로 ADMIN seed 목록 주입
@ConfigurationProperties(prefix = "admin")
public record AdminBootstrapProperties(List<String> kakaoIds) {
    public AdminBootstrapProperties {
        // null 방어 — 환경변수 미설정 시 빈 목록으로 초기화
        kakaoIds = kakaoIds == null ? List.of() : List.copyOf(kakaoIds);
    }

    // kakaoId가 ADMIN seed 목록에 포함되어 있는지 확인
    public boolean isAdmin(String kakaoId) {
        return kakaoIds.contains(kakaoId);
    }
}
