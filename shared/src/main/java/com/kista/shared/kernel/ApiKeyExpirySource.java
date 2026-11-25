package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// API 키 만료일 출처 — 토스·메리츠는 등록·교체 시점 + 1년·사용자 입력
@Getter
@RequiredArgsConstructor
public enum ApiKeyExpirySource {
    NONE("만료 없음"),       // 만료 개념 없음
    ESTIMATED("발급 후 1년"); // 등록 시점 + 1년 또는 사용자 입력

    private final String label; // 한국어 표시 이름 (메타 번들)
}
