package com.kista.trading.domain.strategy;

import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;

// 전략 등록 시 리졸버 4종이 실제로 읽는 원시값만 담은 ISP 좁히기 — 19필드 RegisterStrategyCommand 전체를
// 리졸버에 그대로 넘기지 않는다. StrategyService가 RegisterStrategyCommand에서 값을 꺼내 이 타입으로 변환해 전달한다.
public record StrategyCreationRequest(
        StrategyTicker ticker,          // null이면 설정 기본값
        int divisionCount,               // 0 = 미입력 sentinel (INFINITE 전용)
        Integer intervalWeeks,           // VR 전용, null 허용
        BigDecimal bandWidth,            // VR 전용, null 허용
        Integer recurringAmount          // VR 전용, null 허용
) {}
