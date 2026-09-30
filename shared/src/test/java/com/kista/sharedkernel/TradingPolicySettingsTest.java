package com.kista.sharedkernel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingPolicySettingsTest {

    @Test
    void defaultsPreserveCurrentRuntimeBehavior() {
        TradingPolicySettings settings = TradingPolicySettings.defaults();

        assertThat(settings.brokers()).containsOnlyKeys(Broker.values());
        assertThat(settings.brokers().values()).allMatch(BrokerSettings::enabled);
        assertThat(settings.strategies()).containsOnlyKeys(StrategyType.values());
        assertThat(settings.strategies().values()).allMatch(StrategyCreationSettings::enabled);
        assertThat(settings.strategy(StrategyType.INFINITE).divisionCount())
                .isEqualTo(new StrategyFieldSettings<>(true, List.of(20, 30, 40), 20));
        assertThat(settings.strategy(StrategyType.PRIVACY).ticker())
                .isEqualTo(new StrategyFieldSettings<>(false, List.of(StrategyTicker.SOXL), StrategyTicker.SOXL));
        assertThat(settings.strategy(StrategyType.VR).recurringMode().defaultValue()).isEqualTo(RecurringMode.HOLD);
        assertThat(settings.brokerEnabled(Broker.KIS)).isTrue();
    }

    @Test
    void rejectsMissingKnownBrokerOrStrategyKeys() {
        TradingPolicySettings defaults = TradingPolicySettings.defaults();

        assertThatThrownBy(() -> new TradingPolicySettings(
                Map.of(Broker.KIS, new BrokerSettings(true)), defaults.strategies()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broker");
        assertThatThrownBy(() -> new TradingPolicySettings(defaults.brokers(),
                Map.of(StrategyType.INFINITE, defaults.strategy(StrategyType.INFINITE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy");
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
        StrategyCreationSettings vr = TradingPolicySettings.defaults().strategy(StrategyType.VR);

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
