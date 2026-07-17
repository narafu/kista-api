package com.kista.application.service.trading;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuyPriorityOrderingTest {

    @Mock CycleOrderStrategy vrStrategy;
    @Mock CycleOrderStrategy infiniteStrategy;

    // 정렬 대상 최소 형태 — strategyId/cycleId/type/amount만 있으면 됨
    record Candidate(UUID strategyId, UUID cycleId, Strategy.Type type, BigDecimal amount) {}

    @Test
    void comparator_ordersByPriorityThenAmountThenIds() {
        when(vrStrategy.cycleType()).thenReturn(Strategy.Type.VR);
        lenient().when(vrStrategy.allocationPriority()).thenReturn(0);
        when(infiniteStrategy.cycleType()).thenReturn(Strategy.Type.INFINITE);
        lenient().when(infiniteStrategy.allocationPriority()).thenReturn(1);
        CycleOrderStrategies strategies = new CycleOrderStrategies(List.of(vrStrategy, infiniteStrategy));

        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID id3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        Candidate infiniteBig = new Candidate(id3, id3, Strategy.Type.INFINITE, new BigDecimal("500"));
        Candidate vrSmall = new Candidate(id1, id1, Strategy.Type.VR, new BigDecimal("100"));
        Candidate vrBig = new Candidate(id2, id2, Strategy.Type.VR, new BigDecimal("300"));

        Comparator<Candidate> comparator = BuyPriorityOrdering.comparator(
                strategies, Candidate::type, Candidate::amount, Candidate::strategyId, Candidate::cycleId);

        List<Candidate> sorted = List.of(infiniteBig, vrBig, vrSmall).stream()
                .sorted(comparator)
                .toList();

        // VR(우선순위 0)이 INFINITE(1)보다 먼저, 동일 타입(VR) 내에서는 금액 작은 순
        assertThat(sorted).containsExactly(vrSmall, vrBig, infiniteBig);
    }
}
