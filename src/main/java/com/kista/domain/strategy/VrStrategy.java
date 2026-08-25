package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.VrPosition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static com.kista.domain.model.order.Order.OrderTiming.AT_CLOSE;
import static com.kista.domain.model.order.Order.OrderTiming.AT_OPEN;
import static com.kista.domain.model.order.Order.OrderType.LOC;
import static com.kista.domain.model.order.Order.OrderType.LIMIT;

// VR(밸류리밸런싱) 전략 — 매수·매도 사다리 주문 생성
@Component
public class VrStrategy {

    // 매수·매도 사다리 최대 단 수
    private static final int MAX_RUNGS = 20;

    // 주문 목록 생성 — 아직 첫 포지션을 못 만든 상태(needsBootstrap)면 예산 기반 bootstrap(또는 빈 리스트),
    // 그 외(holdings>0, 사다리로 정상 매수 가능)면 일반 밴드 사다리
    // ticker: 주문에 기록할 거래 종목 (VrPosition은 ticker를 직접 보유하지 않음)
    // referencePrice: bootstrap·캡 판정 공용 기준가 — currentPrice 없으면 전일종가로 대체 가능
    // livePrice: 과거 SELL bootstrap 전용 파라미터 — case1(V만 있음) 폐기로 현재 미사용, 시그니처는 호출부 영향 최소화를 위해 유지
    // 일반 매수·매도 사다리는 생성 시점 가격 캡을 적용하지 않는다 — 접수 전 BuyOrderPriceCapper(VR_POSITION)가 담당
    public List<Order> buildOrders(VrPosition position, Strategy.Ticker ticker,
                                   BigDecimal referencePrice, BigDecimal livePrice, LocalDate tradeDate) {
        if (needsBootstrap(position)) {
            return buildBootstrapBuyOrders(position, ticker, referencePrice, tradeDate);
        }

        // holdings>0인데 value=0인 상태 — bootstrap 매수 체결로 holdings가 생겼지만 V는 롤오버 전까지
        // 스냅샷 그대로 0으로 고정되는 갭 구간. lowerBand/upperBand가 모두 0이 되어 사다리 전 단이
        // $0 가격으로 계산되므로(매수·매도 전부 무의미) 이 구간은 신규 사다리 생성을 skip한다.
        // 다음 롤오버에서 nextValue()가 evaluation(holdings×종가) 기준으로 V를 정상 재확립한다.
        if (position.value().signum() == 0) {
            return List.of();
        }

        List<Order> orders = new ArrayList<>();
        orders.addAll(buildBuyOrders(position, ticker, tradeDate));
        orders.addAll(buildSellOrders(position, ticker, tradeDate));
        return orders;
    }

    // bootstrap 진입 판정 — holdings>0이면 사다리가 항상 정상 작동하므로 대상 아님.
    // holdings=0인데 V=0이면 사다리 공식 자체가 무의미(lowerBand=0)해 bootstrap 필요.
    // holdings=0이고 V>0이어도, 사다리 첫 유효 단(m=2, 가격=lowerBand 그대로)조차 예산을 초과하면
    // 정상 사다리로는 영원히 1주도 못 사므로 마찬가지로 bootstrap 필요 — nextValue() 공식이 매 롤오버마다
    // holdings와 무관하게 V를 키우기 때문에(pool/G+recurringAmount 항), holdings=0이 지속되면 V가 예산
    // 대비 너무 커져버릴 수 있다. 이 판정은 그 상태를 감지해 예산 한도 내 매수로 첫 포지션을 만들어준다.
    private boolean needsBootstrap(VrPosition position) {
        if (position.holdings() != 0) return false;
        if (position.value().signum() == 0) return true;
        return position.lowerBand().compareTo(remainingBudget(position)) > 0;
    }

    // governance 상한 — poolLimit(사이클 개장 시점 예수금×비율) 기준이 원칙이지만,
    // 완전 무일푼(개장 예수금=0)으로 시작한 사이클은 poolLimit이 영구히 0으로 고정되므로 이 경우엔 DB상
    // 예수금(pool)을 그대로 상한으로 대신 쓴다. holdings 여부와 무관 — poolLimit은 사이클 개장 시점에
    // 고정되는 값이라 이후 holdings 변화와 독립적이다. bootstrap(remainingBudget)·사다리(buildBuyLadder)
    // 양쪽이 이 값을 공유해야 두 경로의 "이번 사이클에 쓸 수 있는 예산" 판단이 어긋나지 않는다.
    private BigDecimal governanceLimit(VrPosition position) {
        return position.poolLimit().signum() > 0
                ? position.poolLimit().subtract(position.poolUsed())
                : position.pool();
    }

    // 이번에 매수에 쓸 수 있는 잔여 예산 — 어느 쪽이든 DB상 예수금(pool)은 넘지 않는다.
    private BigDecimal remainingBudget(VrPosition position) {
        return governanceLimit(position).min(position.pool());
    }

    // 첫 포지션 bootstrap — 잔여 예산(remainingBudget)을 오늘의 캡 가격으로 LOC 매수
    // 호출측(needsBootstrap)이 이미 진입 조건을 보장하므로 여기서는 referencePrice 유효성·잔여예산만 확인한다
    // poolUsed는 실제 체결 금액 기준(orderPort.sumFilledBuyAmountByCycleId)이라 하루 주문이 부분/전액 체결되든
    // 미체결이든 다음날 자동으로 정확한 잔여예산이 재계산된다 — 별도 "며칠째"인지 추적 불필요
    private List<Order> buildBootstrapBuyOrders(VrPosition position, Strategy.Ticker ticker,
                                                 BigDecimal referencePrice, LocalDate tradeDate) {
        if (referencePrice == null || referencePrice.signum() <= 0) return List.of();
        BigDecimal remaining = remainingBudget(position);
        if (remaining.signum() <= 0) return List.of();
        BigDecimal price = PriceCapPolicy.capFor(referencePrice);
        int quantity = remaining.divide(price, 0, java.math.RoundingMode.DOWN).intValue();
        if (quantity <= 0) return List.of();
        return List.of(Order.planned(tradeDate, ticker, LOC, BUY, quantity, price, AT_CLOSE,
                Order.leg("VR_BUY", 1)));
    }

    // 매수 사다리 생성 — 최대 MAX_RUNGS단, 1주씩, poolLimit·pool 한도 내
    // 생성 시점 가격 캡은 적용하지 않는다(cap=null) — 접수 전 BuyOrderPriceCapper(VR_POSITION)가 buildCappedBuyOrders로 재산정
    private List<Order> buildBuyOrders(VrPosition position, Strategy.Ticker ticker, LocalDate tradeDate) {
        return buildBuyLadder(position, ticker, tradeDate, null);
    }

    // 접수 직전 가격 캡 보정 — BuyOrderPriceCapper(VR_POSITION)가 최신 현재가 기준 cap으로 사다리를 다시 만든다
    // position은 plan() 시점과 동일 스냅샷(pool·poolUsed·holdings 등) 재사용, cap만 최신 가격 기준으로 교체
    // 주의: 사다리(LIMIT+AT_OPEN) 전용 재산정이다 — bootstrap(LOC+AT_CLOSE, PriceCapPolicy.capFor) 주문에는
    // 호출하면 안 된다. bootstrap은 value=0인 경우이므로 lowerBand=0 → buyPrice(m)=0이 되어 사다리 공식이
    // 무의미해지고, bootstrap 자체의 캡 가격이 통째로 손실된다. 호출측(BuyOrderPriceCapper)이
    // orderType(LOC vs LIMIT)으로 bootstrap 배치를 가려내 이 함수 호출 자체를 막는다.
    public List<Order> buildCappedBuyOrders(VrPosition position, Strategy.Ticker ticker, LocalDate tradeDate, BigDecimal cap) {
        return buildBuyLadder(position, ticker, tradeDate, cap);
    }

    // 매수 사다리 공통 생성 로직 — cap이 null이면 원가 그대로(계획 생성), 비-null이면 캡 적용(접수 전 보정)
    private List<Order> buildBuyLadder(VrPosition position, Strategy.Ticker ticker, LocalDate tradeDate, BigDecimal cap) {
        // pool 사용 가능 잔여액 — remainingBudget()으로 bootstrap과 동일 기준(governanceLimit·pool 이중 상한)을 공유한다.
        // 두 경로가 각자 상한을 계산하면 한쪽만 고친 뒤 다른 쪽이 방치되는 drift가 재발할 수 있어 단일 계산으로 통일했다.
        BigDecimal poolBudget = remainingBudget(position);

        List<Order> rawBuys = new ArrayList<>();
        BigDecimal cumBuyAmount = BigDecimal.ZERO;

        for (int m = 1; m <= MAX_RUNGS; m++) {
            // holdings=0, m=1 → divisor=0: division-by-zero 가드
            int divisor = position.holdings() + m - 1;
            if (divisor < 1) continue;

            // 기본 단가 = lowerBand / divisor
            BigDecimal price = position.buyPrice(m);

            // 가격 캡 적용 — PriceCapPolicy 초과 시 캡 가격으로 교체
            if (cap != null) {
                price = PriceCapPolicy.applyCap(price, cap);
            }

            // poolBudget(governanceLimit·pool 중 더 작은 값) 초과 시 이후 단 전량 제외
            if (cumBuyAmount.add(price).compareTo(poolBudget) > 0) break;

            rawBuys.add(Order.planned(tradeDate, ticker, LIMIT, BUY, 1, price, AT_OPEN));
            cumBuyAmount = cumBuyAmount.add(price);
        }

        // 캡 후 동일 가격의 연속 rung 수량 병합
        return mergeSamePriceOrders(rawBuys, ticker, tradeDate);
    }

    // 연속 동일 가격 BUY 주문 병합 — 1주×N → N주 1건
    private List<Order> mergeSamePriceOrders(List<Order> rawBuys, Strategy.Ticker ticker,
                                             LocalDate tradeDate) {
        if (rawBuys.isEmpty()) return List.of();

        List<Order> merged = new ArrayList<>();
        BigDecimal currentPrice = rawBuys.getFirst().price();
        int currentQty = 0;

        for (Order o : rawBuys) {
            if (o.price().compareTo(currentPrice) == 0) {
                // 같은 가격이면 수량 누적
                currentQty += o.quantity();
            } else {
                // 가격 전환 시 이전 그룹 확정
                merged.add(Order.planned(tradeDate, ticker, LIMIT, BUY, currentQty, currentPrice, AT_OPEN,
                        Order.leg("VR_BUY", merged.size() + 1)));
                currentPrice = o.price();
                currentQty = o.quantity();
            }
        }
        // 마지막 그룹 추가
        merged.add(Order.planned(tradeDate, ticker, LIMIT, BUY, currentQty, currentPrice, AT_OPEN,
                Order.leg("VR_BUY", merged.size() + 1)));
        return merged;
    }

    // 매도 사다리 생성 — 최대 MAX_RUNGS단, 1주씩 (holdings>MAX_RUNGS이면 마지막 단에 잔여 전량)
    private List<Order> buildSellOrders(VrPosition position, Strategy.Ticker ticker,
                                        LocalDate tradeDate) {
        // holdings=0이면 매도 없음
        if (position.holdings() == 0) return List.of();

        int maxS = Math.min(MAX_RUNGS, position.holdings());
        List<Order> sells = new ArrayList<>();

        for (int s = 1; s <= maxS; s++) {
            BigDecimal price = position.sellPrice(s);
            // holdings > MAX_RUNGS이면 마지막 단에 잔여 전량 — holdings - (MAX_RUNGS - 1)
            int quantity = (position.holdings() > MAX_RUNGS && s == MAX_RUNGS)
                    ? position.holdings() - (MAX_RUNGS - 1)
                    : 1;
            sells.add(Order.planned(tradeDate, ticker, LIMIT, SELL, quantity, price, AT_OPEN,
                    Order.leg("VR_SELL", s)));
        }
        return sells;
    }
}
