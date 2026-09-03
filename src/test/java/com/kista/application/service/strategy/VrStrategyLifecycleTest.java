package com.kista.application.service.strategy;

import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyCycleVrDetail;
import com.kista.domain.model.strategy.StrategyDetail;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.trading.application.port.output.StrategyCycleVrPort;
import com.kista.application.port.output.StrategyVrDetailPort;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @DisplayName("findSummary() 활성 VR 상세와 최신 사이클 상세를 합산 — poolLimit은 개장 USD pool×poolLimitRate 파생값")
    void findSummary_combinesActiveDetailAndCycleDetail() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        StrategyCycle latestCycle = new StrategyCycle(
                cycleId, strategyId, versionId, new BigDecimal("1600"), null, LocalDate.now(), null, null, null);
        CyclePosition openingPosition = new CyclePosition(UUID.randomUUID(), cycleId,
                new BigDecimal("1000"), null, null, 0, null, null);
        // 최신 포지션 pool(282.83)은 개장 pool(1000)과 달라야 currentPool이 개장값 재사용이 아님을 검증할 수 있다
        CyclePosition latestPosition = new CyclePosition(UUID.randomUUID(), cycleId,
                new BigDecimal("282.83"), new BigDecimal("71.17"), null, 1, null, null);
        StrategyVrDetail vrDetail = new StrategyVrDetail(versionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        // 총 시작금액 1600과 개장 USD pool 1000은 분리된다. poolLimit은 1000 × 0.50 = 500.00이다.
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                cycleId, new BigDecimal("3000"), 10, new BigDecimal("0.50"));
        when(strategyVrDetailPort.findActiveByStrategyId(strategyId)).thenReturn(Optional.of(vrDetail));
        when(strategyCycleVrPort.findByCycleId(cycleId)).thenReturn(Optional.of(cycleVr));

        Optional<StrategyDetail.VrSummary> result = vrStrategyLifecycle.findSummary(
                strategyId, Optional.of(latestCycle), Optional.of(openingPosition), Optional.of(latestPosition));

        assertThat(result).isPresent();
        assertThat(result.get().intervalWeeks()).isEqualTo(4);
        assertThat(result.get().poolLimit()).isEqualByComparingTo("500.00");
        assertThat(result.get().poolLimitRate()).isEqualByComparingTo("0.50");
        assertThat(result.get().gradient()).isEqualTo(10);
        // currentPool은 최신 포지션 값(282.83) — 개장값(1000)이 아님
        assertThat(result.get().currentPool()).isEqualByComparingTo("282.83");
    }

    @Test
    @DisplayName("findSummary() 최신 포지션이 없으면 currentPool은 null")
    void findSummary_missingLatestPosition_currentPoolIsNull() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        StrategyCycle latestCycle = new StrategyCycle(
                cycleId, strategyId, versionId, new BigDecimal("1600"), null, LocalDate.now(), null, null, null);
        CyclePosition openingPosition = new CyclePosition(UUID.randomUUID(), cycleId,
                new BigDecimal("1000"), null, null, 0, null, null);
        StrategyVrDetail vrDetail = new StrategyVrDetail(versionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                cycleId, new BigDecimal("3000"), 10, new BigDecimal("0.50"));
        when(strategyVrDetailPort.findActiveByStrategyId(strategyId)).thenReturn(Optional.of(vrDetail));
        when(strategyCycleVrPort.findByCycleId(cycleId)).thenReturn(Optional.of(cycleVr));

        Optional<StrategyDetail.VrSummary> result = vrStrategyLifecycle.findSummary(
                strategyId, Optional.of(latestCycle), Optional.of(openingPosition), Optional.empty());

        assertThat(result).isPresent();
        assertThat(result.get().currentPool()).isNull();
    }

    @Test
    @DisplayName("findSummary() 개장 포지션이 없으면 잘못된 저장 상태로 즉시 실패한다")
    void findSummary_missingOpeningPosition_throwsIllegalState() {
        UUID strategyId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        StrategyCycle latestCycle = new StrategyCycle(
                cycleId, strategyId, versionId, new BigDecimal("1600"), null, LocalDate.now(), null, null, null);
        StrategyVrDetail vrDetail = new StrategyVrDetail(versionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                cycleId, new BigDecimal("600"), 10, new BigDecimal("0.50"));
        when(strategyVrDetailPort.findActiveByStrategyId(strategyId)).thenReturn(Optional.of(vrDetail));
        when(strategyCycleVrPort.findByCycleId(cycleId)).thenReturn(Optional.of(cycleVr));

        assertThatThrownBy(() -> vrStrategyLifecycle.findSummary(
                strategyId, Optional.of(latestCycle), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 시작 포지션 없음")
                .hasMessageContaining(cycleId.toString());
    }

    @Test
    @DisplayName("buildSummary() openingPool null이면 잘못된 저장 상태로 즉시 실패한다")
    void buildSummary_nullOpeningPool_throwsIllegalState() {
        UUID versionId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        StrategyVrDetail vrDetail = new StrategyVrDetail(versionId, 4, new BigDecimal("15.00"), 0,
                10, 52, 26, 10, new BigDecimal("0.50"), 52, 26, new BigDecimal("0.50"));
        StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
                cycleId, new BigDecimal("3000"), 10, new BigDecimal("0.50"));

        assertThatThrownBy(() -> vrStrategyLifecycle.buildSummary(vrDetail, cycleVr, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VR 시작 포지션 없음");
    }
}
