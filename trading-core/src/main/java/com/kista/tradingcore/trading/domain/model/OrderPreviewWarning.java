package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 다음 주문 미리보기 경고 — kista-ui computeOrderReadiness 판정을 옮긴 SSOT. 미리보기의 점검값(ledgerCheck·accountCashCheck·accountHoldingsCheck)과 주문 목록에서만 파생한다
public record OrderPreviewWarning(
        Code code,     // 경고 코드 (메타 orderPreviewWarningCodes)
        String detail  // 한국어 안내 문장
) {
    @Getter
    @RequiredArgsConstructor
    public enum Code {
        LEDGER_SHORTFALL("전략 원장 예수금 부족"),      // 원장 부족으로 신규 BUY 전체 거절
        ACCOUNT_CASH_SHORTFALL("계좌 예수금 부족"),     // 계좌 주문가능 USD 부족 — 증권사가 BUY를 거절할 수 있음
        ACCOUNT_CASH_PARTIAL("보정 주문 거절 가능"),    // 부족하지만 보정 주문을 빼면 충분 — 일부 BUY만 거절될 수 있음
        HOLDINGS_SHORTFALL("보유 수량 부족"),          // 판매 가능 수량 부족 — 증권사가 SELL을 거절할 수 있음
        BUY_UNPLACED("매수 미접수"),                   // 접수 시점이 지난 BUY 계획이 오늘 미접수
        SELL_UNPLACED("매도 미접수");                  // 접수 시점이 지난 SELL 계획이 오늘 미접수

        private final String label; // 한국어 표시 이름 (메타 번들)
    }

    public static List<OrderPreviewWarning> of(NextOrdersPreview preview) {
        List<PlannedOrder> orders = preview.orders();
        List<Order> todayOrders = preview.todayOrders();
        Set<OrderDirection> planned = orders.stream().map(PlannedOrder::direction).collect(Collectors.toSet());
        // AT_CLOSE는 마감 배치 대상이라 개장 선접수(AT_OPEN)만 된 시점엔 아직 접수 전 — 오늘 AT_CLOSE 주문이 하나라도 있으면 마감 접수가 시도된 것으로 본다
        boolean closePlaced = todayOrders.stream().anyMatch(o -> o.timing() == OrderTiming.AT_CLOSE);
        Set<OrderDirection> due = orders.stream()
                .filter(o -> o.timing() != OrderTiming.AT_CLOSE || closePlaced)
                .map(PlannedOrder::direction).collect(Collectors.toSet());
        Set<OrderDirection> placed = todayOrders.stream().map(Order::direction).collect(Collectors.toSet());
        boolean hasBuy = planned.contains(OrderDirection.BUY);
        boolean hasSell = planned.contains(OrderDirection.SELL);

        List<OrderPreviewWarning> warnings = new ArrayList<>();
        // 원장 부족 — BUY 계획이 있을 때만
        LedgerCheckPreview ledger = preview.ledgerCheck();
        boolean ledgerRejected = hasBuy && ledger != null && !ledger.sufficient();
        if (ledgerRejected) {
            warnings.add(new OrderPreviewWarning(Code.LEDGER_SHORTFALL, "전략 원장 예수금이 부족해 매수가 접수되지 않습니다(원장 %s / 필요 %s)."
                    .formatted(usd(ledger.remainingLedger()), usd(ledger.requiredBuyUsd()))));
        }
        // 계좌 예수금 — 계좌 단위 값이라 BUY 계획이 있을 때만, 원장 거절이면 BUY가 나가지 않아 생략
        AccountCashCheckPreview cash = preview.accountCashCheck();
        if (hasBuy && !ledgerRejected && cash != null && !cash.sufficient()) {
            String amounts = "주문가능 %s / 계좌 전체 접수 필요 %s".formatted(usd(cash.availableUsd()), usd(cash.requiredBuyUsd()));
            boolean partial = false; // 부족이지만 보정 주문을 빼면 충분 — 부족·부분 부족 중 하나만 싣는다
            if (cash.correctionBuyUsd().signum() > 0) {
                // 보정 주문 지정가는 접수 시 묶이지만 체결은 단위금액 안에서 끝난다 — 보정분을 빼면 충분한지로 부분 부족을 가른다
                BigDecimal excluded = cents(cash.requiredBuyUsd()).subtract(cents(cash.correctionBuyUsd()));
                amounts += " · 보정 주문 제외 " + usd(excluded);
                partial = cents(cash.availableUsd()).compareTo(excluded) >= 0;
            }
            warnings.add(partial
                    ? new OrderPreviewWarning(Code.ACCOUNT_CASH_PARTIAL, "계좌 예수금이 부족해 보정 주문이 거절될 수 있습니다(%s).".formatted(amounts))
                    : new OrderPreviewWarning(Code.ACCOUNT_CASH_SHORTFALL, "계좌 예수금이 부족해 매수가 거절될 수 있습니다(%s).".formatted(amounts)));
        }
        // 보유 수량 — (계좌, 종목) 단위 값이라 SELL 계획이 있을 때만
        AccountHoldingsCheckPreview holdings = preview.accountHoldingsCheck();
        if (hasSell && holdings != null && !holdings.sufficient()) {
            warnings.add(new OrderPreviewWarning(Code.HOLDINGS_SHORTFALL, "보유 수량이 부족해 매도가 거절될 수 있습니다(판매가능 %s / 종목 전체 필요 %s)."
                    .formatted(holdings.availableQuantity().stripTrailingZeros().toPlainString(),
                            holdings.requiredSellQuantity().stripTrailingZeros().toPlainString())));
        }
        // 방향별 미접수 — 오늘 시도 자체가 없으면(todayOrders 비어 있음) 판정하지 않는다
        if (!todayOrders.isEmpty()) {
            if (due.contains(OrderDirection.BUY) && !placed.contains(OrderDirection.BUY)) {
                warnings.add(new OrderPreviewWarning(Code.BUY_UNPLACED, "접수 시점이 지난 매수 주문이 오늘 접수되지 않았습니다."));
            }
            if (due.contains(OrderDirection.SELL) && !placed.contains(OrderDirection.SELL)) {
                warnings.add(new OrderPreviewWarning(Code.SELL_UNPLACED, "접수 시점이 지난 매도 주문이 오늘 접수되지 않았습니다."));
            }
        }
        return List.copyOf(warnings);
    }

    // USD 금액 자릿수로 반올림 — 경계값 비교를 표시값 기준으로 맞춘다
    private static BigDecimal cents(BigDecimal usd) {
        return usd.setScale(Currency.USD.amountScale(), RoundingMode.HALF_UP);
    }

    // USD 전용 경로 — 표기는 Currency.display
    private static String usd(BigDecimal amount) {
        return Currency.USD.display(amount);
    }
}
