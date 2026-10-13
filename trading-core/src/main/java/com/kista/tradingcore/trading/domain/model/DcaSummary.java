package com.kista.tradingcore.trading.domain.model;

import com.kista.tradingcore.matching.domain.model.DcaConfig;

import java.time.LocalDate;

// DCA 전략 조회 요약 — 설정 + 다음 판정 예정일(보정 반영) + 최근 회차
public record DcaSummary(
        DcaConfig config,              // 활성 버전 설정
        LocalDate nextUsSessionDate,   // 다음 판정 예정 US 세션일 (캘린더 미존재 시 null)
        LocalDate nextKstTradeDate,    // 위와 짝인 KST 거래일 (null 동반)
        DcaExecution lastExecution     // 최근 dca_execution (없으면 null)
) {}
