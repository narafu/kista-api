package com.kista.tradingcore.trading.domain.creation;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.domain.creation.StrategyCreationResolver.ResolvedCreation;
import com.kista.tradingcore.trading.domain.model.RegisterStrategyCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.kista.tradingcore.support.StrategyCommands.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VrRegistrationTest {

    private static final BigDecimal EVALUATED = new BigDecimal("3000");

    // 테스트용 VR 명령 — 예수금·주기·적립액·V값만 바꿔 가며 쓴다
    private static RegisterStrategyCommand cmd(BigDecimal deposit, Integer recurring, BigDecimal vrValue, VrRampInput ramp) {
        return vr().ticker(StrategyTicker.TQQQ).deposit(deposit).intervalWeeks(4).bandWidth("15").recurringAmount(recurring).vrRamp(ramp).vrValue(vrValue).build();
    }

    private static ResolvedCreation resolved(Integer intervalWeeks, Integer recurring) {
        return new ResolvedCreation(StrategyTicker.TQQQ, 0, intervalWeeks, new BigDecimal("15"), recurring);
    }

    @Test
    void resolve_explicitVrValueOverridesEvaluated() {
        var cmd = cmd(BigDecimal.ZERO, 0, new BigDecimal("5000"), VrRampInput.NONE);
        assertThat(VrRegistration.resolve(cmd, resolved(4, 0), EVALUATED).vrValue()).isEqualByComparingTo("5000");
    }

    @Test
    void resolve_zeroVrValueFallsBackToEvaluated() {
        var cmd = cmd(BigDecimal.ZERO, 0, BigDecimal.ZERO, VrRampInput.NONE);
        assertThat(VrRegistration.resolve(cmd, resolved(4, 0), EVALUATED).vrValue()).isEqualByComparingTo("3000");
    }

    @Test
    void resolve_negativeVrValue_throws() {
        var cmd = cmd(BigDecimal.ZERO, 0, new BigDecimal("-1"), VrRampInput.NONE);
        assertThatThrownBy(() -> VrRegistration.resolve(cmd, resolved(4, 0), EVALUATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("VR 전략의 초기 V값(initialVrValue)은 0 이상이어야 합니다");
    }

    @Test
    void resolve_withdrawalUsesEvaluatedNotOverride() {
        var cmd = cmd(BigDecimal.ZERO, -100, new BigDecimal("1000000"), VrRampInput.NONE);
        assertThatThrownBy(() -> VrRegistration.resolve(cmd, resolved(4, -100), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("인출식 VR 전략의 초기 자산은 10000.00 이상이어야 합니다");
    }

    @Test
    void resolve_intervalWeeksCheckedFirst() {
        // 램프 무효(gradient 음수)와 intervalWeeks=0 동시 — intervalWeeks 문구가 먼저
        var badRamp = new VrRampInput(-1, null, null, null, null, null, null, null);
        var cmd = cmd(BigDecimal.ZERO, 0, null, badRamp);
        assertThatThrownBy(() -> VrRegistration.resolve(cmd, resolved(0, 0), EVALUATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("VR 전략의 리밸런싱 주기(intervalWeeks)는 1 이상이어야 합니다");
    }
}
