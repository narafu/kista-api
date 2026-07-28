package com.kista.domain.model.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// StrategyVrDetail.gradientAt(long)/poolLimitRateAt(long) 경과주수 기반 램프 공식 검증 (constraints.md "VR 공식" 후속)
@DisplayName("StrategyVrDetail 램프 공식 검증")
class StrategyVrDetailTest {

    // 기본 램프 파라미터: gGraceWeeks=pGraceWeeks=52, gStepWeeks=pStepWeeks=26, gMax=15, poolLimitFloor=0.50
    private StrategyVrDetail detail(int gGraceWeeks, int gStepWeeks, int pGraceWeeks, int pStepWeeks) {
        return new StrategyVrDetail(
                UUID.randomUUID(), 4, new BigDecimal("15.00"), 0,
                10, gGraceWeeks, gStepWeeks, 15,
                new BigDecimal("0.75"), pGraceWeeks, pStepWeeks, new BigDecimal("0.50"));
    }

    private StrategyVrDetail defaultDetail() {
        return detail(52, 26, 52, 26);
    }

    @Test
    @DisplayName("weeks=0(경과 없음) → gradientAt/poolLimitRateAt 초기값 그대로")
    void weeks0_returnsInitialValues() {
        StrategyVrDetail detail = defaultDetail();

        assertThat(detail.gradientAt(0)).isEqualTo(10);
        assertThat(detail.poolLimitRateAt(0)).isEqualByComparingTo("0.75");
    }

    @Test
    @DisplayName("weeks=51(유예기간 내, grace 직전) → 초기값 유지")
    void weeks51_withinGrace_returnsInitialValues() {
        StrategyVrDetail detail = defaultDetail();

        assertThat(detail.gradientAt(51)).isEqualTo(10);
        assertThat(detail.poolLimitRateAt(51)).isEqualByComparingTo("0.75");
    }

    @Test
    @DisplayName("weeks=52(grace와 정확히 같음) → 즉시 1단계 적용")
    void weeks52_atGraceBoundary_appliesFirstStepImmediately() {
        StrategyVrDetail detail = defaultDetail();

        assertThat(detail.gradientAt(52)).isEqualTo(11);
        assertThat(detail.poolLimitRateAt(52)).isEqualByComparingTo("0.70");
    }

    @Test
    @DisplayName("weeks=77(2단계 경계 직전) → 1단계 값 유지")
    void weeks77_beforeSecondStep_staysAtFirstStep() {
        StrategyVrDetail detail = defaultDetail();

        assertThat(detail.gradientAt(77)).isEqualTo(11);
        assertThat(detail.poolLimitRateAt(77)).isEqualByComparingTo("0.70");
    }

    @Test
    @DisplayName("weeks=78(52+26, 2단계 경계) → 2단계 적용")
    void weeks78_atSecondStepBoundary_appliesSecondStep() {
        StrategyVrDetail detail = defaultDetail();

        assertThat(detail.gradientAt(78)).isEqualTo(12);
        assertThat(detail.poolLimitRateAt(78)).isEqualByComparingTo("0.65");
    }

    @Test
    @DisplayName("weeks=104(52+26*2, 3단계 경계) → 3단계 적용")
    void weeks104_atThirdStepBoundary_appliesThirdStep() {
        StrategyVrDetail detail = defaultDetail();

        assertThat(detail.gradientAt(104)).isEqualTo(13);
        assertThat(detail.poolLimitRateAt(104)).isEqualByComparingTo("0.60");
    }

    @Test
    @DisplayName("gMax 상한 클램프 — 매우 큰 경과주수여도 gMax를 절대 넘지 않음")
    void gradientAt_clampsAtGMax() {
        StrategyVrDetail detail = defaultDetail();

        assertThat(detail.gradientAt(10_000)).isEqualTo(15);
    }

    @Test
    @DisplayName("poolLimitFloor 하한 클램프 — 매우 큰 경과주수여도 poolLimitFloor 아래로 내려가지 않음")
    void poolLimitRateAt_clampsAtFloor() {
        StrategyVrDetail detail = defaultDetail();

        assertThat(detail.poolLimitRateAt(10_000)).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("gGraceWeeks·pGraceWeeks가 다르면 두 램프가 서로 독립적으로 동작")
    void differentGraceWeeks_rampsMoveIndependently() {
        // gGraceWeeks=52(아직 유예 중) vs pGraceWeeks=10(이미 유예 종료) — 같은 weeks=15에서 결과가 갈려야 함
        StrategyVrDetail detail = detail(52, 26, 10, 26);

        assertThat(detail.gradientAt(15)).isEqualTo(10); // gradient는 아직 유예 중 → 초기값 유지
        assertThat(detail.poolLimitRateAt(15)).isEqualByComparingTo("0.70"); // poolLimitRate는 이미 1단계 하강
    }

    @Test
    @DisplayName("poolLimitRateAt 결과는 scale=2로 반올림된다")
    void poolLimitRateAt_isScaledToTwoDecimals() {
        StrategyVrDetail detail = defaultDetail();

        assertThat(detail.poolLimitRateAt(78).scale()).isEqualTo(2);
        assertThat(detail.poolLimitRateAt(0).scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("pStepWeeks=0 → poolLimitRate 램프 비활성화, 경과주수와 무관하게 initialPoolLimitRate 고정")
    void pStepWeeksZero_disablesRamp_staysAtInitialValue() {
        StrategyVrDetail detail = detail(52, 26, 52, 0);

        assertThat(detail.poolLimitRateAt(0)).isEqualByComparingTo("0.75");
        assertThat(detail.poolLimitRateAt(10_000)).isEqualByComparingTo("0.75");
    }
}
