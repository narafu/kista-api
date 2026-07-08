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
        List<Order> sellOrders = buildSellOrders(explicitSells, nullSellTemplate, balance, multiple);
        return Stream.concat(buyOrders.stream(), sellOrders.stream()).toList();
    }

    // 명시 SELL + null SELL("잔량 전부") 합산하여 Order 리스트 반환
    private List<Order> buildSellOrders(List<PrivacyTrade> explicit, PrivacyTrade nullTemplate,
                                        AccountBalance balance, BigDecimal multiple) {
        // 명시 SELL은 multiple만 적용 — balance cap 없음 (브로커가 실제 보유량으로 판단)
        List<Order> result = explicit.stream()
                .map(t -> Order.planned(t.tradeDate(), t.ticker(), t.orderType(), SELL,
                        applyMultiple(t.quantity(), multiple), t.price(), AT_CLOSE))
                .toList();

        if (nullTemplate == null) return result;

        // null SELL = balance.holdings() - 명시 SELL 합 (음수면 제외)
        int sumExplicit = result.stream().mapToInt(Order::quantity).sum();
        int remaining = balance.holdings() - sumExplicit;
        if (remaining <= 0) {
            log.warn("[PRIVACY] 잔량 매도 SELL 제외 — 명시 SELL 합이 잔량 이상: balance={}, sumExplicit={}",
                    balance.holdings(), sumExplicit);
            return result;
        }
        List<Order> withNull = new ArrayList<>(result);
        withNull.add(Order.planned(nullTemplate.tradeDate(), nullTemplate.ticker(),
                nullTemplate.orderType(), SELL, remaining, nullTemplate.price(), AT_CLOSE));
        return withNull;
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

    // 기준 매매표 수량에 multiple 비율을 곱해 실제 주문 수량 산출 (소수점 버림)
    private static int applyMultiple(int quantity, BigDecimal multiple) {
        return BigDecimal.valueOf(quantity)
                .multiply(multiple)
                .setScale(0, RoundingMode.DOWN)
                .intValue();
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
