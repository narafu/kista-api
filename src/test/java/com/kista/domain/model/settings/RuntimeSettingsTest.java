package com.kista.domain.model.settings;

import com.kista.domain.model.account.Account.Broker;
import com.kista.domain.model.strategy.Strategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeSettingsTest {

    @Test
    void defaultsPreserveCurrentRuntimeBehavior() {
        RuntimeSettings settings = RuntimeSettings.defaults();

        assertThat(settings.approvalRequired()).isTrue();
        assertThat(settings.brokers()).containsOnlyKeys(Broker.values());
        assertThat(settings.brokers().values()).allMatch(RuntimeSettings.BrokerSettings::enabled);
        assertThat(settings.strategies()).containsOnlyKeys(Strategy.Type.values());
        assertThat(settings.strategies().values()).allMatch(StrategyCreationSettings::enabled);
        assertThat(settings.strategies().get(Strategy.Type.INFINITE).divisionCount())
                .isEqualTo(new StrategyFieldSettings<>(true, List.of(20, 30, 40), 20));
        assertThat(settings.strategies().get(Strategy.Type.PRIVACY).ticker())
                .isEqualTo(new StrategyFieldSettings<>(false, List.of(Strategy.Ticker.SOXL), Strategy.Ticker.SOXL));
        assertThat(settings.strategies().get(Strategy.Type.VR).recurringMode().defaultValue())
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
                Map.of(Strategy.Type.INFINITE, defaults.strategies().get(Strategy.Type.INFINITE))))
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
        StrategyCreationSettings vr = RuntimeSettings.defaults().strategies().get(Strategy.Type.VR);

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
