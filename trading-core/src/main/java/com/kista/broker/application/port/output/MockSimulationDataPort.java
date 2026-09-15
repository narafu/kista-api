package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.PlacedOrderView;
import com.kista.broker.domain.model.PositionView;
import com.kista.broker.domain.model.StrategyRefLite;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// MockBrokerAdapter 전용 — 실제 증권사 API 없이 trading이 소유한 영속 데이터(주문·사이클·포지션)를 조회하기 위한 broker 소유 포트.
// AlpacaCalendarAdapter(adapter.out.alpaca) → MarketHolidayStorePort(application.port.output) → MarketCalendarPersistenceAdapter
// (persistence.calendar) 패턴을 역방향 적용한 것 — 데이터를 필요로 하는 쪽(broker)이 포트를 정의하고,
// 데이터를 가진 쪽(trading)이 구현한다. 반환 타입도 broker 소유 얇은 뷰 레코드(Order/CyclePosition 전체가 아닌
// MockBrokerAdapter가 실제로 읽는 필드만)로 제한한다.
public interface MockSimulationDataPort {

    // 전략의 현재 활성 사이클 ID — StrategyCycle 전체가 아닌 id만 필요
    UUID findActiveCycleId(UUID strategyId);

    // 사이클·거래일 기준 PLACED 주문 조회 (체결 시뮬레이션 대상)
    List<PlacedOrderView> findPlacedOrders(UUID cycleId, LocalDate tradeDate);

    // 전략 기준 최신 포지션 스냅샷 (없으면 empty — 아직 체결 이력 없음)
    Optional<PositionView> findLatestPosition(UUID strategyId);

    // 계좌에 속한 전략 목록(id+ticker만) — MockBrokerAdapter가 계좌+ticker로 전략을 해석할 때 사용
    List<StrategyRefLite> findStrategiesByAccountId(UUID accountId);
}
