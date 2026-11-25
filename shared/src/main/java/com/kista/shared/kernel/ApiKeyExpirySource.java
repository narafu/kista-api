package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// API 키 만료일 출처 — 토스·메리츠는 추정(+1년)·사용자 입력
@Getter
@RequiredArgsConstructor
public enum ApiKeyExpirySource {
    NONE("만료 없음"),       // 만료 개념 없음
    ESTIMATED("추정 만료일"); // 등록 시점 기준 추정 또는 사용자 입력

    private final String label; // 한국어 표시 이름 (메타 번들)
}
