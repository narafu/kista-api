package com.kista.shared.kernel;

import java.math.BigDecimal;

// 주문 수량 정규화 — DB numeric(20,8) 왕복 뒤 생기는 꼬리 0을 걷어 equals·JSON을 정수 주식 수량과 같게 맞춘다
public final class Quantities {

    private Quantities() {}

    // stripTrailingZeros()만 쓰면 100 → 1E+2(JSON 지수 표기)라 음수 scale은 0으로 되돌린다
    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
