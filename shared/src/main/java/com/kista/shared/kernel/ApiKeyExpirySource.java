package com.kista.shared.kernel;

// API 키 만료일 출처 — 토스·메리츠는 추정(+1년)·사용자 입력
public enum ApiKeyExpirySource {
    NONE,       // 만료 개념 없음
    ESTIMATED   // 등록 시점 기준 추정 또는 사용자 입력
}
