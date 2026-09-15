package com.kista.broker.domain.model;

import java.math.BigDecimal;

// MockBrokerAdapter 잔고 시뮬레이션 전용 뷰 — trading 소유 CyclePosition 필드 중 실제로 읽는 것만 담는다
// (MockSimulationDataPort.findLatestPosition 반환 타입 — trading 쪽 MockSimulationDataAdapter가 매핑해 생성)
public record PositionView(
        int holdings,          // 보유 수량
        BigDecimal avgPrice,   // 평균 매입 단가 (holdings=0이면 null)
        BigDecimal usdDeposit  // 통합주문가능금액
) {}
