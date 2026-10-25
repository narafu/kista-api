package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static com.kista.shared.kernel.OrderDirection.BUY;
import static com.kista.shared.kernel.OrderDirection.SELL;
import static com.kista.shared.kernel.OrderTiming.AT_OPEN;
import static com.kista.shared.kernel.OrderType.*;

public class InfiniteStrategy {

    // 장마감 하락 대비 보정 주문 추가 횟수
    private static final int CORRECTION_ORDER_COUNT = 3;

    // 전반 매수①(평단가) / 매수②(기준가) / ①② 동가 병합 leg — canSkipOrderComputation도 같은 상수로 판정
    static final String EARLY_AVG_LEG = "INFINITE_EARLY_AVG_BUY";
    static final String EARLY_REF_LEG = "INFINITE_EARLY_REF_BUY";
    static final String EARLY_MERGED_LEG = "INFINITE_EARLY_MERGED_BUY";
    // 매수① 자리로 식별하는 leg(병합 포함)
    private static final Set<String> AVG_LEGS = Set.of(EARLY_AVG_LEG, EARLY_MERGED_LEG);

    // 전략 계산 — 전반/후반 분기 후 주문 목록 반환
    public List<PlannedOrder> buildOrders(InfinitePosition position, LocalDate tradeDate) {
        return position.isEarlyStage()
                ? buildEarlyStageOrders(position, tradeDate)
                : buildLateStageOrders(position, tradeDate);
    }

    // BUY PLANNED 가격이 cap을 초과할 때 cap 기준으로 매수 수량 재산정 + 보정 주문(1주 LOC × 3회) 추가
    // 입력에 이미 correction leg(같은 배치 내 재접수 시점 추가 하락으로 재캡되는 경우)가 섞여 있으면
    // base 주문(평단가/기준가)만 재산정 대상으로 삼는다 — 없으면 그대로 base로 취급
    public List<PlannedOrder> buildCappedBuyOrders(InfinitePosition position, LocalDate tradeDate, List<PlannedOrder> buyOrders, BigDecimal cap) {
        BigDecimal unitAmount = position.unitAmount(); // 단위금액 (실값)
        List<PlannedOrder> baseOrders = buyOrders.stream().filter(order -> !isCorrectionLeg(order)).toList();
        // base 주문 없이 correction leg만 남은 비정상 상태(부분 저장 실패 등) — 재산정 근거가 없어 보정 없이 원본 유지
        if (baseOrders.isEmpty()) return buyOrders;

        // 원래 BUY 주문의 가격 순서: buy①=averagePrice(또는 currentPrice), buy②=referencePrice
        // 전반이면 ①②(매수②는 0주라 없을 수 있다), 후반이면 단일 LOC
        List<PlannedOrder> newBuys = !position.isEarlyStage()
                ? computeLateBuys(tradeDate, position, baseOrders.getFirst(), cap, unitAmount)
                : computeEarlyBuys(tradeDate, position, baseOrders, cap, unitAmount);

        return withCorrections(tradeDate, position, newBuys);
    }

    // BUY 지정가×수량 합이 budget을 넘는 동안 뒤쪽 correction leg부터 제거 — 보정 가격이 누적수량 의존이라 앞쪽(prefix)만 남긴다
    // base(비-correction) 주문은 건드리지 않는다(수량 축소는 매매 공식 이탈) — 다 빼도 초과면 base만 반환
    public static List<PlannedOrder> trimCorrectionsToBudget(List<PlannedOrder> buys, BigDecimal budget) {
        List<PlannedOrder> trimmed = new ArrayList<>(buys);
        while (AccountBalance.buyTotal(trimmed).compareTo(budget) > 0) {
            int last = trimmed.size() - 1;
            while (last >= 0 && !isCorrectionLeg(trimmed.get(last))) last--;
            if (last < 0) break;
            trimmed.remove(last);
        }
        return trimmed;
    }

    // BUY 목록 뒤에 보정 주문 3건을 붙이고, 원장 예수금을 넘으면 뒤쪽부터 생략한다 — 지정가 합계가 예산 밖이면 접수 전체가 거절돼 교착된다
    private List<PlannedOrder> withCorrections(LocalDate tradeDate, InfinitePosition position, List<PlannedOrder> buys) {
        List<PlannedOrder> result = new ArrayList<>(buys);
        for (int i = 0; i < CORRECTION_ORDER_COUNT; i++) {
            addCorrectionOrder(tradeDate, position, result, position.unitAmount(), i + 1);
        }
        return trimCorrectionsToBudget(result, position.usdDeposit());
    }

    // 후반 단일 LOC 매수: unitAmount/referencePrice
    private List<PlannedOrder> computeLateBuys(LocalDate tradeDate, InfinitePosition position, PlannedOrder orig, BigDecimal cap, BigDecimal unitAmount) {
        BigDecimal cappedPrice = orig.price().min(cap);
        int quantity = InfinitePosition.lateBuyQuantity(unitAmount, cappedPrice);
        List<PlannedOrder> buys = new ArrayList<>();
        if (quantity > 0) buys.add(PlannedOrder.of(
                tradeDate, position.ticker(), orig.orderType(), BUY, quantity, cappedPrice, orig.orderLeg()));
        return buys;
    }

    // 전반: buy①(averagePrice 기반), buy②(referencePrice 기반, 원본에 없을 수 있음)
    // DB 조회 순서가 보장되지 않으므로 ①②는 순서가 아니라 leg로 식별한다 — ①②로 식별되지 않는 행(legacy UNKNOWN_LEG 등)은 비어 있는 자리를 ①→② 순서로 채운다
    private List<PlannedOrder> computeEarlyBuys(LocalDate tradeDate, InfinitePosition position, List<PlannedOrder> baseOrders, BigDecimal cap, BigDecimal unitAmount) {
        PlannedOrder buy1 = baseOrders.stream().filter(o -> AVG_LEGS.contains(o.orderLeg())).findFirst().orElse(null);
        PlannedOrder buy2 = baseOrders.stream().filter(o -> EARLY_REF_LEG.equals(o.orderLeg())).findFirst().orElse(null);
        Iterator<PlannedOrder> legacy = baseOrders.stream()
                .filter(o -> !AVG_LEGS.contains(o.orderLeg()) && !EARLY_REF_LEG.equals(o.orderLeg())).iterator();
        if (buy1 == null && legacy.hasNext()) buy1 = legacy.next();
        // 병합 행은 ①②를 함께 대표하므로 legacy 행을 매수②로 쓰지 않는다
        if (buy2 == null && legacy.hasNext() && !EARLY_MERGED_LEG.equals(buy1.orderLeg())) buy2 = legacy.next();
        // 매수①이 없으면(REF만 남은 경우) 평단가 LOC로 다시 만든다
        BigDecimal avgPrice = buy1 != null ? buy1.price() : position.averagePrice();
        OrderType avgType = buy1 != null ? buy1.orderType() : LOC;
        String avgLeg = buy1 != null && !AVG_LEGS.contains(buy1.orderLeg()) ? buy1.orderLeg() : EARLY_AVG_LEG;
        BigDecimal refPrice = buy2 != null ? buy2.price() : position.referencePrice();
        OrderType refType = buy2 != null ? buy2.orderType() : LOC;
        String refLeg = buy2 != null ? buy2.orderLeg() : EARLY_REF_LEG;

        BigDecimal cappedAvg = avgPrice.min(cap);
        BigDecimal cappedRef = refPrice.min(cap);

        int quantity1 = InfinitePosition.earlyBuyQuantity1(unitAmount, cappedAvg);
        int quantity2 = InfinitePosition.earlyBuyQuantity2(unitAmount, cappedAvg, quantity1, cappedRef);

        List<PlannedOrder> buys = new ArrayList<>();
        if (cappedAvg.compareTo(cappedRef) == 0) {
            // cappedAvg == cappedRef이면 병합
            buys.add(PlannedOrder.of(tradeDate, position.ticker(), avgType, BUY,
                    quantity1 + quantity2, cappedAvg, EARLY_MERGED_LEG));
        } else {
            buys.add(PlannedOrder.of(tradeDate, position.ticker(), avgType, BUY, quantity1, cappedAvg, avgLeg));
            if (quantity2 > 0) buys.add(PlannedOrder.of(tradeDate, position.ticker(), refType, BUY, quantity2, cappedRef, refLeg));
        }
        return buys;
    }

    // correction 주문(INFINITE_CORRECTION_01~03) 여부 — 재진입 캡 계산 시 base 주문과 분리하기 위함
    private static boolean isCorrectionLeg(PlannedOrder order) {
        return order.orderLeg() != null && order.orderLeg().startsWith("INFINITE_CORRECTION_");
    }

    private void addCorrectionOrder(LocalDate tradeDate, InfinitePosition position, List<PlannedOrder> newBuys,
                                    BigDecimal unitAmount, int correctionIndex) {
        int totalQuantity = newBuys.stream().mapToInt(PlannedOrder::quantity).sum();
        if (totalQuantity == 0) {
            return;
        }

        BigDecimal adjustedOrderPrice = unitAmount.divide(BigDecimal.valueOf(totalQuantity + 1), 2, RoundingMode.FLOOR);

        newBuys.add(PlannedOrder.of(tradeDate, position.ticker(), LOC, BUY, 1, adjustedOrderPrice,
                PlannedOrder.leg("INFINITE_CORRECTION", correctionIndex)));
    }

    private List<PlannedOrder> buildEarlyStageOrders(InfinitePosition position, LocalDate tradeDate) {
        List<PlannedOrder> buys = new ArrayList<>();
        // LOC 매수 ① — 평단가 기준 (최소 1주)
        int buyQuantity1 = position.calcEarlyBuyQuantityByAvgPrice();
        buys.add(PlannedOrder.of(tradeDate, position.ticker(), LOC, BUY, buyQuantity1,
                position.averagePrice(), EARLY_AVG_LEG));

        // LOC 매수 ② — 기준가 기준 (매수①이 unitAmount를 다 쓰면 0주 → 미생성)
        int buyQuantity2 = position.calcEarlyBuyQuantityByRefPrice(buyQuantity1);
        if (buyQuantity2 > 0) buys.add(PlannedOrder.of(tradeDate, position.ticker(), LOC, BUY, buyQuantity2,
                position.referencePrice(), EARLY_REF_LEG));

        // 보정 주문은 BUY만 담긴 시점에 붙인다 — base BUY가 없으면 보정도 없다
        List<PlannedOrder> orders = withCorrections(tradeDate, position, buys);
        addCommonSellOrders(orders, position, tradeDate);
        return orders;
    }

    private List<PlannedOrder> buildLateStageOrders(InfinitePosition position, LocalDate tradeDate) {
        List<PlannedOrder> orders = new ArrayList<>();
        if (position.isFinalRound()) {
            int mocSellQuantity = position.calcMocSellQuantity();
            if (mocSellQuantity >= 1) {
                // 최종회차 MOC 매도 — 개장 시 선접수
                orders.add(PlannedOrder.of(tradeDate, position.ticker(), MOC, SELL, mocSellQuantity,
                        BigDecimal.ZERO, AT_OPEN, "INFINITE_MOC_SELL"));
            }
        } else {
            // LOC 매수 — 기준가 기준
            int buyQuantity = position.calcLateBuyQuantity();
            if (buyQuantity >= 1) {
                orders.add(PlannedOrder.of(tradeDate, position.ticker(), LOC, BUY, buyQuantity,
                        position.referencePrice(), "INFINITE_LATE_REF_BUY"));
            }

            orders = withCorrections(tradeDate, position, orders);
            addCommonSellOrders(orders, position, tradeDate);
        }
        return orders;
    }

    private void addCommonSellOrders(List<PlannedOrder> orders, InfinitePosition position, LocalDate tradeDate) {
        // LOC 매도 (기준가 + 0.01) — 개장 시 선접수
        int locSellQuantity = position.calcLocSellQuantity();
        if (locSellQuantity >= 1) {
            BigDecimal locSellPrice = position.referencePrice().add(InfinitePosition.TICK_SIZE);
            orders.add(PlannedOrder.of(tradeDate, position.ticker(), LOC, SELL, locSellQuantity,
                    locSellPrice, AT_OPEN, "INFINITE_LOC_SELL"));
        }

        // 지정가 매도 (목표가) — 개장 시 선접수
        int limitSellQuantity = position.calcLimitSellQuantity();
        if (limitSellQuantity >= 1) {
            orders.add(PlannedOrder.of(tradeDate, position.ticker(), LIMIT, SELL, limitSellQuantity,
                    position.targetPrice(), AT_OPEN, "INFINITE_LIMIT_SELL"));
        }
    }
}
