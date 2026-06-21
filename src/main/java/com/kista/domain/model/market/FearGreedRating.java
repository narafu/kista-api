package com.kista.domain.model.market;

// 공포탐욕지수 등급 — 크립토(alternative.me)·CNN 공통 사용
public enum FearGreedRating {
    EF, // Extreme Fear
    F,  // Fear
    N,  // Neutral
    G,  // Greed
    EG; // Extreme Greed

    // API 응답 문자열(대소문자 무관)을 enum으로 변환
    public static FearGreedRating fromLabel(String label) {
        return switch (label.toLowerCase().trim()) {
            case "extreme fear"  -> EF;
            case "fear"          -> F;
            case "neutral"       -> N;
            case "greed"         -> G;
            case "extreme greed" -> EG;
            default -> throw new IllegalArgumentException("알 수 없는 공포탐욕 등급: " + label);
        };
    }
}
