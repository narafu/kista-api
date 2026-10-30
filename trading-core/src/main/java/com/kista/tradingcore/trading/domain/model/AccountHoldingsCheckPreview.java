package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;

// 미리보기 시 계좌 실제 보유(판매 가능) 수량 대비 오늘 SELL 경고 — (계좌, 종목) 단위·조회 시점 추정, 매매 판정에는 쓰지 않는다
public record AccountHoldingsCheckPreview(
        BigDecimal availableQuantity,      // 증권사 판매 가능 수량 (미체결 매도 차감 전 보유 수량)
        BigDecimal requiredSellQuantity,   // 종목 전체 오늘 SELL 필요 수량 (PLANNED·SUBMITTING·PLACED SELL + 전략별 신규 SELL)
        boolean sufficient                 // false면 접수 시 증권사가 SELL을 거절할 수 있다
) {}
