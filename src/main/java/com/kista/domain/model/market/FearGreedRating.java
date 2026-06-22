package com.kista.domain.model.market;

// 공포탐욕지수 등급 — 크립토(alternative.me)·CNN 공통 사용
public enum FearGreedRating {
    EXTREME_FEAR,
    FEAR,
    NEUTRAL,
    GREED,
    EXTREME_GREED;

    // API 응답 문자열(대소문자 무관)을 enum으로 변환
    public static FearGreedRating fromLabel(String label) {
        return switch (label.toLowerCase().trim()) {
            case "extreme fear"  -> EXTREME_FEAR;
            case "fear"          -> FEAR;
            case "neutral"       -> NEUTRAL;
            case "greed"         -> GREED;
            case "extreme greed" -> EXTREME_GREED;
            default -> throw new IllegalArgumentException("알 수 없는 공포탐욕 등급: " + label);
        };
    }
}
