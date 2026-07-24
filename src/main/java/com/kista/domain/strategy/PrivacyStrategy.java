package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeBase.PrivacyTrade;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static com.kista.domain.model.order.Order.OrderTiming.AT_CLOSE;

@Slf4j
@Component
public class PrivacyStrategy {

    // initialUsdDeposit ÷ privacyTradeBase.currentCycleStart() 로 배수를 동적 산출
    public List<Order> buildOrders(AccountBalance balance, BigDecimal initialUsdDeposit, PrivacyTradeBase privacyTradeBase) {
        if (initialUsdDeposit == null || initialUsdDeposit.signum() <= 0) {
            throw new IllegalStateException("[PRIVACY] initialUsdDeposit 이상: " + initialUsdDeposit);
        }
        BigDecimal start = privacyTradeBase.currentCycleStart();
        BigDecimal multiple = initialUsdDeposit.divide(start, 2, RoundingMode.FLOOR);
        log.info("[PRIVACY] 배수 산출: initialUsdDeposit={}, currentCycleStart={}, multiple={}", initialUsdDeposit, start, multiple);

        List<BuyEntry> buyEntries = new ArrayList<>();
        List<PrivacyTrade> explicitSells = new ArrayList<>();
        PrivacyTrade nullSellTemplate = null; // null quantity SELL — "잔량 전부 매도" 후보

        // BUY/SELL 분리 — BUY null은 skip, SELL null은 단 1개만 허용
        for (PrivacyTrade t : privacyTradeBase.trades()) {
            if (t.direction() == BUY) {
                if (t.quantity() == null) {
                    log.warn("[PRIVACY] BUY 수량 미정 건너뜀: ticker={}, price={}", t.ticker(), t.price());
                    continue;
                }
                // 배수 적용 — 버림은 Order 생성 직전에만 (중간 버림 시 소수점 배수에서 수량 손실 발생)
                BigDecimal qty = BigDecimal.valueOf(t.quantity()).multiply(multiple);
                buyEntries.add(new BuyEntry(t.price(), qty, t.orderType(), t.tradeDate(), t.ticker()));
            } else {
                if (t.quantity() == null) {
                    if (nullSellTemplate != null) {
                        throw new IllegalStateException("[PRIVACY] SELL null quantity는 1개만 허용: ticker=" + t.ticker());
                    }
                    nullSellTemplate = t;
                } else {
                    explicitSells.add(t);
                }
            }
        }

        // 기준표 목표 보유량과 현재 잔량의 차이로 BUY 가감 (실수 기준 — 버림 전)
        BigDecimal target = BigDecimal.valueOf(privacyTradeBase.holdings()).multiply(multiple);
        BigDecimal diff = target.subtract(BigDecimal.valueOf(balance.holdings()));
        log.info("[PRIVACY] 보유 보정: target={}, current={}, diff={}", target, balance.holdings(), diff);
        adjustBuyQuantities(buyEntries, diff);

        // 버린 소수점 합산 → 정수 보너스를 작은 매수(가장 낮은 가격)에 보정
        BigDecimal totalFraction = buyEntries.stream()
                .map(e -> e.quantity.subtract(e.quantity.setScale(0, RoundingMode.DOWN)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int bonus = totalFraction.setScale(0, RoundingMode.DOWN).intValue();
        if (bonus > 0) {
            buyEntries.stream()
                    .min(Comparator.comparing(e -> e.price))
                    .ifPresent(smallest -> {
                        smallest.quantity = smallest.quantity.add(BigDecimal.valueOf(bonus));
                        log.info("[PRIVACY] 버림 보정: totalFraction={}, bonus={}", totalFraction, bonus);
                    });
        }

        // BUY (버림 후 quantity>0만) + SELL 합쳐 반환
        List<Order> buyOrders = new ArrayList<>();
        for (BuyEntry e : buyEntries) {
            int qty = e.quantity.setScale(0, RoundingMode.DOWN).intValue();
            if (qty > 0) {
                buyOrders.add(Order.planned(e.tradeDate, e.ticker, e.orderType, BUY, qty, e.price, AT_CLOSE));
            }
        }
        buyOrders = assignSequentialLegs(sortOrdersForStableLegs(buyOrders), "PRIVACY_BUY");
        List<Order> sellOrders = assignSequentialLegs(
                sortOrdersForStableLegs(buildSellOrders(explicitSells, nullSellTemplate, balance, multiple)), "PRIVACY_SELL");
        return Stream.concat(buyOrders.stream(), sellOrders.stream()).toList();
    }

    private List<Order> sortOrdersForStableLegs(List<Order> orders) {
        return orders.stream()
                .sorted(Comparator
                        .comparing(Order::direction)
                        .thenComparing((Order order) -> order.direction() == BUY
                                ? order.price().negate()
                                : order.price())
                        .thenComparing(Order::orderType)
                        .thenComparing(Order::quantity, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private List<Order> assignSequentialLegs(List<Order> orders, String prefix) {
        List<Order> result = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            result.add(orders.get(i).withLeg(Order.leg(prefix, i + 1)));
        }
        return result;
    }

    // 명시 SELL + null SELL("잔량 전부") 합산하여 Order 리스트 반환
    private List<Order> buildSellOrders(List<PrivacyTrade> explicit, PrivacyTrade nullTemplate,
                                        AccountBalance balance, BigDecimal multiple) {
        // 명시 SELL — 버림 전 실수 수량 보존 (fraction 보정을 위해)
        List<BigDecimal> rawQtys = explicit.stream()
                .map(t -> BigDecimal.valueOf(t.quantity()).multiply(multiple))
                .toList();

        List<Order> result = new ArrayList<>();
        for (int i = 0; i < explicit.size(); i++) {
            PrivacyTrade t = explicit.get(i);
            int qty = rawQtys.get(i).setScale(0, RoundingMode.DOWN).intValue();
            result.add(Order.planned(t.tradeDate(), t.ticker(), t.orderType(), SELL, qty, t.price(), AT_CLOSE));
        }

        // null SELL 없는 경우만 fraction 보정 — null SELL이 있으면 remaining이 자동 흡수
        if (nullTemplate == null && !result.isEmpty()) {
            BigDecimal totalFraction = rawQtys.stream()
                    .map(q -> q.subtract(q.setScale(0, RoundingMode.DOWN)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int bonus = totalFraction.setScale(0, RoundingMode.DOWN).intValue();
            if (bonus > 0) {
                // 가장 높은 가격 SELL에 보너스 추가 (높은 가격이 먼저 체결 — BUY는 낮은 가격)
                int maxIdx = 0;
                for (int i = 1; i < result.size(); i++) {
                    if (result.get(i).price().compareTo(result.get(maxIdx).price()) > 0) {
                        maxIdx = i;
                    }
                }
                Order max = result.get(maxIdx);
                result.set(maxIdx, Order.planned(
                        max.tradeDate(), max.ticker(), max.orderType(), SELL,
                        max.quantity() + bonus, max.price(), AT_CLOSE));
                log.info("[PRIVACY] SELL 버림 보정: totalFraction={}, bonus={}", totalFraction, bonus);
            }
        }

        // 명시 SELL 합이 실제 보유수량을 초과하면 가장 싼 SELL부터 차례로 차감 — BUY의 adjustBuyQuantities(보유
        // 초과 시 가장 비싼 BUY부터 차감)와 대칭으로, 보유하지 않은 수량을 매도 시도해 판매가능수량 부족으로
        // 전량 거절되는 상황을 방지한다
        result = capSellQuantitiesToHoldings(result, balance.holdings());

        if (nullTemplate == null) return result;

        // null SELL = balance.holdings() - 명시 SELL 합 (음수면 제외)
        int sumExplicit = result.stream().mapToInt(Order::quantity).sum();
        int remaining = balance.holdings() - sumExplicit;
        if (remaining <= 0) {
            log.warn("[PRIVACY] 잔량 매도 SELL 제외 — 명시 SELL 합이 잔량 이상: balance={}, sumExplicit={}",
                    balance.holdings(), sumExplicit);
            return result;
        }
        result.add(Order.planned(nullTemplate.tradeDate(), nullTemplate.ticker(),
                nullTemplate.orderType(), SELL, remaining, nullTemplate.price(), AT_CLOSE));
        return result;
    }

    // SELL 합계가 실제 보유수량을 초과하면 가장 싼 SELL부터 차례로 차감 — 0이 된 leg는 제외
    private List<Order> capSellQuantitiesToHoldings(List<Order> sells, int holdings) {
        int totalQty = sells.stream().mapToInt(Order::quantity).sum();
        int excess = totalQty - holdings;
        if (excess <= 0) return sells;

        log.warn("[PRIVACY] SELL 합계가 보유수량 초과 — 가장 싼 SELL부터 차감: totalQty={}, holdings={}, excess={}",
                totalQty, holdings, excess);

        int[] quantities = sells.stream().mapToInt(Order::quantity).toArray();
        List<Integer> cheapestFirst = java.util.stream.IntStream.range(0, sells.size()).boxed()
                .sorted(Comparator.comparing(i -> sells.get(i).price()))
                .toList();

        int remaining = excess;
        for (int idx : cheapestFirst) {
            if (remaining == 0) break;
            int take = Math.min(remaining, quantities[idx]);
            quantities[idx] -= take;
            remaining -= take;
        }

        List<Order> capped = new ArrayList<>();
        for (int i = 0; i < sells.size(); i++) {
            if (quantities[i] > 0) {
                capped.add(quantities[i] == sells.get(i).quantity() ? sells.get(i) : sells.get(i).withQuantity(quantities[i]));
            }
        }
        return capped;
    }

    private void adjustBuyQuantities(List<BuyEntry> buyEntries, BigDecimal diff) {
        if (diff.signum() == 0) return;
        if (buyEntries.isEmpty()) {
            log.warn("[PRIVACY] BUY 후보 없음 — 보유 차이 보정 불가: diff={}", diff);
            return;
        }
        if (diff.signum() > 0) {
            // 보유 부족 — 가장 싼 BUY에 부족분 전량 추가
            buyEntries.sort(Comparator.comparing(e -> e.price));
            buyEntries.getFirst().quantity = buyEntries.getFirst().quantity.add(diff);
        } else {
            // 보유 초과 — 가장 비싼 BUY부터 차례로 차감
            buyEntries.sort(Comparator.comparing((BuyEntry e) -> e.price).reversed());
            BigDecimal remaining = diff.negate();
            for (BuyEntry entry : buyEntries) {
                if (remaining.signum() == 0) break;
                BigDecimal take = remaining.min(entry.quantity);
                entry.quantity = entry.quantity.subtract(take);
                remaining = remaining.subtract(take);
            }
        }
    }

    // BUY 주문 quantity 조정을 위한 가변 컨테이너 (record 불가 — quantity 변경 필요)
    private static class BuyEntry {
        final BigDecimal price;
        BigDecimal quantity; // 배수 적용 실수 — 최종 Order 생성 시에만 버림 적용
        final Order.OrderType orderType;
        final LocalDate tradeDate;
        final Ticker ticker;

        BuyEntry(BigDecimal price, BigDecimal quantity, Order.OrderType orderType, LocalDate tradeDate, Ticker ticker) {
            this.price = price;
            this.quantity = quantity;
            this.orderType = orderType;
            this.tradeDate = tradeDate;
            this.ticker = ticker;
        }
    }
}
