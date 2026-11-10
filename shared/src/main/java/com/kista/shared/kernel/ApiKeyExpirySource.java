package com.kista.shared.kernel;

// API 키 만료일 출처 — 업비트는 API 조회, KIS·토스·메리츠는 추정(+1년)·사용자 입력
public enum ApiKeyExpirySource {
    NONE,       // 만료 개념 없음
    BROKER_API, // 거래소 API가 만료일을 알려줌
    ESTIMATED   // 등록 시점 기준 추정 또는 사용자 입력
}
