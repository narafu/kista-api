package com.kista.tradingcore.trading.domain.model;

import java.util.function.Supplier;

// 요청 필드 하나에 귀속되는 입력 거절 — HTTP 400은 detail 요약 + fieldErrors[{field, detail}](TradingProblemDetails), 검증 전용 엔드포인트는 200 fieldErrors로 같은 {field, detail}을 싣는다
public class FieldRejectedException extends IllegalArgumentException {

    private final String field; // 요청 JSON 필드 이름

    public FieldRejectedException(String field, String message) {
        super(message);
        this.field = field;
    }

    private FieldRejectedException(String field, IllegalArgumentException cause) {
        super(cause.getMessage(), cause);
        this.field = field;
    }

    public String field() {
        return field;
    }

    // 필드를 모르는 공용 검사(shared.kernel·matching)의 IAE를 같은 문장 그대로 field에 귀속시킨다 — 이미 귀속된 거절은 그대로 둔다
    public static <T> T attribute(String field, Supplier<T> step) {
        try {
            return step.get();
        } catch (FieldRejectedException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new FieldRejectedException(field, e);
        }
    }

    // 반환값 없는 검사용
    public static void attribute(String field, Runnable step) {
        attribute(field, () -> {
            step.run();
            return null;
        });
    }
}
