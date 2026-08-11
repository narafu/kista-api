package com.kista.domain.model.asset;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AssetCategory {
    INVESTMENT("투자"),
    SAVINGS("예적금"),
    LOAN("대출"),        // 부채 판정 기준값 — 순자산·총부채 집계는 이 값을 기준으로 나뉜다
    REAL_ESTATE("부동산");

    private final String label; // 한국어 표시명 — 와이어 값은 enum name() 그대로(INVESTMENT 등), 라벨은 UI 전용
}
