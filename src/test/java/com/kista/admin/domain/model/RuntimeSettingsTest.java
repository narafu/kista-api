package com.kista.admin.domain.model;

import com.kista.account.domain.model.Account.Broker;
import com.kista.strategyconfig.domain.model.Strategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

class RuntimeSettingsTest {

    @Test
    void defaultsPreserveCurrentRuntimeBehavior() {
        RuntimeSettings settings = RuntimeSettings.defaults();

        assertThat(settings.approvalRequired()).isTrue();
        assertThat(settings.brokers()).containsOnlyKeys(Broker.values());
        assertThat(settings.brokers().values()).allMatch(RuntimeSettings.BrokerSettings::enabled);
        assertThat(settings.strategies()).containsOnlyKeys(StrategyType.values());
        assertThat(settings.strategies().values()).allMatch(StrategyCreationSettings::enabled);
        assertThat(settings.strategies().get(StrategyType.INFINITE).divisionCount())
                .isEqualTo(new StrategyFieldSettings<>(true, List.of(20, 30, 40), 20));
        assertThat(settings.strategies().get(StrategyType.PRIVACY).ticker())
                .isEqualTo(new StrategyFieldSettings<>(false, List.of(StrategyTicker.SOXL), StrategyTicker.SOXL));
        assertThat(settings.strategies().get(StrategyType.VR).recurringMode().defaultValue())
                .isEqualTo(RecurringMode.HOLD);
    }

    @Test
    void rejectsMissingKnownBrokerOrStrategyKeys() {
        RuntimeSettings defaults = RuntimeSettings.defaults();

        assertThatThrownBy(() -> new RuntimeSettings(true,
                Map.of(Broker.KIS, new RuntimeSettings.BrokerSettings(true)), defaults.strategies()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broker");
        assertThatThrownBy(() -> new RuntimeSettings(true, defaults.brokers(),
                Map.of(StrategyType.INFINITE, defaults.strategies().get(StrategyType.INFINITE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy");
    }

    @Test
    void backwardCompatConstructorFillsBenchmarksDefaults() {
        // benchmarks 도입 이전 호출부(3-arg 생성자)도 기본값을 채워야 한다.
        RuntimeSettings defaults = RuntimeSettings.defaults();
        RuntimeSettings viaThreeArg = new RuntimeSettings(true, defaults.brokers(), defaults.strategies());

        assertThat(viaThreeArg.benchmarks()).isEqualTo(BenchmarkSettings.defaults());
    }

    @Test
    void fieldRequiresAllowedDefaultAndSingleValueWhenFixed() {
        assertThatThrownBy(() -> new StrategyFieldSettings<>(true, List.of(10, 20), 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default");
        assertThatThrownBy(() -> new StrategyFieldSettings<>(false, List.of(10, 20), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-customizable");
    }

    @Test
    void fixedFieldAppliesDefaultForOmissionAndRejectsExplicitChange() {
        StrategyFieldSettings<String> field = new StrategyFieldSettings<>(false, List.of("SOXL"), "SOXL");

        assertThat(field.resolve(null)).isEqualTo("SOXL");
        assertThatThrownBy(() -> field.resolve("TQQQ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-customizable");
    }

    @Test
    void fixedRecurringModeMustBeHold() {
        StrategyCreationSettings vr = RuntimeSettings.defaults().strategies().get(StrategyType.VR);

        assertThatThrownBy(() -> new StrategyCreationSettings(true, vr.ticker(), null,
                new StrategyFieldSettings<>(false, List.of(RecurringMode.DEPOSIT), RecurringMode.DEPOSIT),
                vr.bandWidth(), vr.intervalWeeks()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOLD");
        assertThatThrownBy(() -> new StrategyCreationSettings(true, vr.ticker(), null,
                new StrategyFieldSettings<>(false, List.of(RecurringMode.WITHDRAW), RecurringMode.WITHDRAW),
                vr.bandWidth(), vr.intervalWeeks()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOLD");
    }
}
