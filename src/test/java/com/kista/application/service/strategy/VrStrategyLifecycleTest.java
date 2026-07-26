package com.kista.application.service.strategy;

import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyCycleVrDetail;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.domain.port.out.StrategyCycleVrPort;
import com.kista.domain.port.out.StrategyVrDetailPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VrStrategyLifecycle 단위 테스트")
class VrStrategyLifecycleTest {

    @Mock StrategyVrDetailPort strategyVrDetailPort;
    @Mock StrategyCycleVrPort strategyCycleVrPort;

    @InjectMocks VrStrategyLifecycle vrStrategyLifecycle;

    @Test
    @DisplayName("saveVersionDetail() recurringAmount null이면 0으로 정규화한다 — 램프 8필드는 호출측 정규화값을 그대로 저장")
    void saveVersionDetail_normalizesNullRecurringAmount() {
        UUID versionId = UUID.randomUUID();
        when(strategyVrDetailPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyVrDetail result = vrStrategyLifecycle.saveVersionDetail(
                versionId, 4, new BigDecimal("15.00"), null,
                10, 52, 26, 10, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.75"));

        assertThat(result.strategyVersionId()).isEqualTo(versionId);
        assertThat(result.recurringAmount()).isZero();
        assertThat(result.initialGradient()).isEqualTo(10);
        assertThat(result.gMax()).isEqualTo(10);
        assertThat(result.initialPoolLimitRate()).isEqualByComparingTo("0.75");
        assertThat(result.poolLimitFloor()).isEqualByComparingTo("0.75");
        verify(strategyVrDetailPort).save(any(StrategyVrDetail.class));
    }

    @Test
    @DisplayName("saveInitialCycleDetail() 등록 시점(경과 0주) gradient·poolLimitRate 스냅샷 저장 — poolLimit 달러 계산은 더 이상 이 계층에서 하지 않는다")
    void saveInitialCycleDetail_snapshotsGradientAndPoolLimitRateAtZeroWeeks() {
        UUID cycleId = UUID.randomUUID();
        // gGraceWeeks/pGraceWeeks=52 → 경과 0주는 항상 유예기간 이내라 initial* 값이 그대로 스냅샷된다
        StrategyVrDetail vrDetail = new StrategyVrDetail(UUID.randomUUID(), 4, new BigDecimal("15.00"), 100,
                10, 52, 26, 15, new BigDecimal("0.75"), 52, 26, new BigDecimal("0.50"));
        when(strategyCycleVrPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyCycleVrDetail result = vrStrategyLifecycle.saveInitialCycleDetail(
                cycleId, new BigDecimal("1000"), new BigDecimal("3000"), vrDetail);

        assertThat(result.strategyCycleId()).isEqualTo(cycleId);
        assertThat(result.value()).isEqualByComparingTo("3000");
        // gradientAt(0)/poolLimitRateAt(0) == initialGradient/initialPoolLimitRate (gMax=15여도 유예기간 이내라 미적용)
        assertThat(result.gradient()).isEqualTo(10);
        assertThat(result.poolLimitRate()).isEqualByComparingTo("0.75");
    }

    @Test
    @DisplayName("saveInitialCycleDetail() initialValue null이면 V=0으로 정규화한다")
    void saveInitialCycleDetail_nullInitialValue_defaultsToZero() {
        UUID cycleId = UUID.randomUUID();
        StrategyVrDetail vrDetail = new StrategyVrDetail(UUID.randomUUID(), 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        when(strategyCycleVrPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StrategyCycleVrDetail result = vrStrategyLifecycle.saveInitialCycleDetail(
                cycleId, BigDecimal.ZERO, null, vrDetail);

        assertThat(result.value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("findSummary() 활성 VR 상세와 최신 사이클 상세를 합산 — poolLimit은 startAmount×poolLimitRate 파생값")
    void findSummary_combinesActiveDetailAndCycleDetail() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        StrategyCycle latestCycle = new StrategyCycle(
                cycleId, strategyId, versionId, new BigDecimal("2000"), null, LocalDate.now(), null, null, null);
        StrategyVrDetail vrDetail = new StrategyVrDetail(versionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        // poolLimitRate=0.50 — latestCycle.startAmount()(2000) × 0.50 = 1000.00 파생 검증
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                cycleId, new BigDecimal("3000"), 10, new BigDecimal("0.50"));
        when(strategyVrDetailPort.findActiveByStrategyId(strategyId)).thenReturn(Optional.of(vrDetail));
        when(strategyCycleVrPort.findByCycleId(cycleId)).thenReturn(Optional.of(cycleVr));

        Optional<StrategyDetail.VrSummary> result = vrStrategyLifecycle.findSummary(
                strategyId, Optional.of(latestCycle));

        assertThat(result).isPresent();
        assertThat(result.get().intervalWeeks()).isEqualTo(4);
        assertThat(result.get().poolLimit()).isEqualByComparingTo("1000.00");
        assertThat(result.get().poolLimitRate()).isEqualByComparingTo("0.50");
        assertThat(result.get().gradient()).isEqualTo(10);
    }

    @Test
    @DisplayName("buildSummary() startAmount null이면 poolLimit도 null (사이클 미존재 등 방어)")
    void buildSummary_nullStartAmount_yieldsNullPoolLimit() {
        UUID versionId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        StrategyVrDetail vrDetail = new StrategyVrDetail(versionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                cycleId, new BigDecimal("3000"), 10, new BigDecimal("0.50"));

        StrategyDetail.VrSummary result = vrStrategyLifecycle.buildSummary(vrDetail, cycleVr, null);

        assertThat(result.poolLimit()).isNull();
    }
}
