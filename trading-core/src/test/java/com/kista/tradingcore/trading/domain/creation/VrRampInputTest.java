package com.kista.tradingcore.trading.domain.creation;

import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.StrategyVrDetail;
import com.kista.tradingcore.trading.domain.model.RegisterStrategyCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VrRampInputTest {

    @Test
    void withDefaults_accumulate() {
        VrRampParams p = VrRampInput.NONE.withDefaults(100);
        assertThat(p.initialGradient()).isEqualTo(10);
        assertThat(p.gGraceWeeks()).isEqualTo(52);
        assertThat(p.gStepWeeks()).isEqualTo(26);
        assertThat(p.gMax()).isEqualTo(20);
        assertThat(p.initialPoolLimitRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(p.pGraceWeeks()).isEqualTo(52);
        assertThat(p.pStepWeeks()).isEqualTo(26);
        assertThat(p.poolLimitFloor()).isEqualByComparingTo("0.5");
    }

    @Test
    void withDefaults_hold() {
        VrRampParams p = VrRampInput.NONE.withDefaults(0);
        assertThat(p.initialGradient()).isEqualTo(10);
        assertThat(p.gMax()).isEqualTo(20);
        assertThat(p.initialPoolLimitRate()).isEqualByComparingTo("0.75");
        assertThat(p.poolLimitFloor()).isEqualByComparingTo("0.5");
    }

    @Test
    void withDefaults_withdraw() {
        VrRampParams p = VrRampInput.NONE.withDefaults(-100);
        assertThat(p.initialGradient()).isEqualTo(40);
        assertThat(p.gMax()).isEqualTo(50);
        assertThat(p.initialPoolLimitRate()).isEqualByComparingTo("0.1");
        assertThat(p.poolLimitFloor()).isEqualByComparingTo("0.1");
    }

    @Test
    void withDefaults_explicitValuesWin() {
        VrRampInput in = new VrRampInput(11, 1, 2, 33, new BigDecimal("0.6"), 3, 4, new BigDecimal("0.2"));
        VrRampParams p = in.withDefaults(-100);
        assertThat(p.initialGradient()).isEqualTo(11);
        assertThat(p.gGraceWeeks()).isEqualTo(1);
        assertThat(p.gStepWeeks()).isEqualTo(2);
        assertThat(p.gMax()).isEqualTo(33);
        assertThat(p.initialPoolLimitRate()).isEqualByComparingTo("0.6");
        assertThat(p.pGraceWeeks()).isEqualTo(3);
        assertThat(p.pStepWeeks()).isEqualTo(4);
        assertThat(p.poolLimitFloor()).isEqualByComparingTo("0.2");
    }

    private static StrategyVrDetail detail() {
        return new StrategyVrDetail(UUID.randomUUID(), 2, new BigDecimal("15.00"), 100, 12, 30, 13, 25,
                new BigDecimal("0.80"), 31, 14, new BigDecimal("0.30"));
    }

    @Test
    void inheritFrom_nullFieldsTakeCurrent() {
        StrategyVrDetail d = detail();
        VrRampParams p = VrRampInput.NONE.inheritFrom(d);
        assertThat(p).isEqualTo(new VrRampParams(d.initialGradient(), d.gGraceWeeks(), d.gStepWeeks(), d.gMax(),
                d.initialPoolLimitRate(), d.pGraceWeeks(), d.pStepWeeks(), d.poolLimitFloor()));
    }

    @Test
    void inheritFrom_explicitFieldOverrides() {
        StrategyVrDetail d = detail();
        VrRampParams p = new VrRampInput(15, null, null, null, null, null, null, null).inheritFrom(d);
        assertThat(p.initialGradient()).isEqualTo(15);
        assertThat(p.gGraceWeeks()).isEqualTo(d.gGraceWeeks());
        assertThat(p.gStepWeeks()).isEqualTo(d.gStepWeeks());
        assertThat(p.gMax()).isEqualTo(d.gMax());
        assertThat(p.initialPoolLimitRate()).isEqualTo(d.initialPoolLimitRate());
        assertThat(p.pGraceWeeks()).isEqualTo(d.pGraceWeeks());
        assertThat(p.pStepWeeks()).isEqualTo(d.pStepWeeks());
        assertThat(p.poolLimitFloor()).isEqualTo(d.poolLimitFloor());
    }

    @Test
    void registerCommand_nullRamp_normalizedToNone() {
        RegisterStrategyCommand cmd = new RegisterStrategyCommand(StrategyType.VR, null, null, null, 0, null, null,
                null, null, null, null, null, null, null);
        assertThat(cmd.vrRamp()).isEqualTo(VrRampInput.NONE);
    }
}
