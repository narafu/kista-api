package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;

// 미리보기 시 계좌 실제 주문가능 USD 대비 오늘 BUY 경고 — 계좌 단위·조회 시점 추정, 매매 판정에는 쓰지 않는다
public record AccountCashCheckPreview(
        BigDecimal availableUsd,     // 증권사 USD 주문가능금액 (이미 접수된 주문은 증권사가 차감)
        BigDecimal requiredBuyUsd,   // 계좌 전체 오늘 BUY 필요 금액 (미접수 PLANNED·SUBMITTING + 전략별 신규 BUY)
        boolean sufficient           // false면 접수 시 증권사가 BUY를 거절할 수 있다
) {}
