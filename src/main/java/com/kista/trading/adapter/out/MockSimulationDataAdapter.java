package com.kista.trading.adapter.out;

import com.kista.trading.application.port.output.StrategyLookupPort;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.OrderType;
import com.kista.broker.domain.model.PlacedOrderView;
import com.kista.broker.domain.model.PositionView;
import com.kista.broker.domain.model.StrategyRefLite;
import com.kista.broker.application.port.output.MockSimulationDataPort;
import com.kista.common.CycleLookups;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.Order;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// MockBrokerAdapter(broker 모듈)가 정의한 MockSimulationDataPort 구현체 — trading 소유 영속 포트(OrderPort/
// CyclePositionPort/StrategyCyclePort)를 내부적으로 호출해 broker 소유 뷰 레코드로 매핑한다.
// trading→broker(정상, 유지 방향) 참조만 발생 — broker는 더 이상 trading 타입을 참조하지 않는다.
@Component
@RequiredArgsConstructor
class MockSimulationDataAdapter implements MockSimulationDataPort {

    private final OrderPort orderPort;
    private final CyclePositionPort cyclePositionPort;
    private final StrategyCyclePort strategyCyclePort;
    private final StrategyLookupPort strategyPort;

    @Override
    public List<StrategyRefLite> findStrategiesByAccountId(UUID accountId) {
        return strategyPort.findByAccountId(accountId).stream()
                .map(s -> new StrategyRefLite(s.id(), s.ticker()))
                .toList();
    }

    @Override
    public UUID findActiveCycleId(UUID strategyId) {
        return CycleLookups.requireLatestCycle(strategyCyclePort, strategyId).id();
    }

    @Override
    public List<PlacedOrderView> findPlacedOrders(UUID cycleId, LocalDate tradeDate) {
        return orderPort.findPlacedByCycleAndDate(cycleId, tradeDate).stream()
                .map(MockSimulationDataAdapter::toPlacedOrderView)
                .toList();
    }

    @Override
    public Optional<PositionView> findLatestPosition(UUID strategyId) {
        return cyclePositionPort.findLatestOneByStrategyId(strategyId).map(MockSimulationDataAdapter::toPositionView);
    }

    private static PlacedOrderView toPlacedOrderView(Order order) {
        return new PlacedOrderView(toDirection(order.direction()), toOrderType(order.orderType()),
                order.quantity(), order.price(), order.externalOrderId());
    }

    private static PositionView toPositionView(CyclePosition position) {
        return new PositionView(position.holdings(), position.avgPrice(), position.usdDeposit());
    }

    private static Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> Direction.BUY;
            case SELL -> Direction.SELL;
        };
    }

    private static OrderType toOrderType(Order.OrderType orderType) {
        return switch (orderType) {
            case LOC -> OrderType.LOC;
            case MOC -> OrderType.MOC;
            case LIMIT -> OrderType.LIMIT;
        };
    }
}
