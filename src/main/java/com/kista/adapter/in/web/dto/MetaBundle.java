package com.kista.adapter.in.web.dto;

import java.util.List;

public record MetaBundle(
        List<StrategyTypeMeta> strategyTypes,  // 전략 타입 목록
        List<TickerMeta> tickers,              // 티커 목록
        List<EnumMeta> brokers,                // 증권사 목록
        List<EnumMeta> strategyStatuses,       // 전략 상태 목록
        List<EnumMeta> cycleSeedTypes          // 연속 사이클 정책 목록
) {}
