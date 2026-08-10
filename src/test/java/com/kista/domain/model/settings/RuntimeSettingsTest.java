package com.kista.domain.model.settings;

import com.kista.domain.model.account.Account.Broker;
import com.kista.domain.model.asset.AssetCategory;
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
        assertThat(settings.assetFormOptions().subcategorySuggestions()).containsOnlyKeys(AssetCategory.values());
        assertThat(settings.assetFormOptions().strategySuggestions()).contains("VR", "INFINITE", "PRIVACY", "DCA");
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
    void backwardCompatConstructorFillsBenchmarksAndAssetFormOptionsDefaults() {
        // benchmarks·assetFormOptions 도입 이전 호출부(3-arg 생성자)도 둘 다 기본값을 채워야 한다.
        RuntimeSettings defaults = RuntimeSettings.defaults();
        RuntimeSettings viaThreeArg = new RuntimeSettings(true, defaults.brokers(), defaults.strategies());

        assertThat(viaThreeArg.benchmarks()).isEqualTo(BenchmarkSettings.defaults());
        assertThat(viaThreeArg.assetFormOptions()).isEqualTo(AssetFormOptions.defaults());
    }

    @Test
    void assetFormOptionsBackfillsMissingCategoryKeyWithEmptyListInsteadOfRejecting() {
        // brokers/strategies 맵과 달리 이 필드는 순수 추천 목록이라 누락 키를 거부하지 않고 빈 목록으로 채운다 —
        // 완전성을 강제하면 향후 AssetCategory 추가 시 기존 저장 행이 전부 역직렬화 실패로 죽는다.
        AssetFormOptions defaults = AssetFormOptions.defaults();

        AssetFormOptions partial = new AssetFormOptions(
                Map.of(AssetCategory.INVESTMENT, defaults.subcategorySuggestions().get(AssetCategory.INVESTMENT)),
                defaults.institutionSuggestions(), defaults.assetClassSuggestions(), defaults.strategySuggestions());

        assertThat(partial.subcategorySuggestions()).containsOnlyKeys(AssetCategory.values());
        assertThat(partial.subcategorySuggestions().get(AssetCategory.INVESTMENT))
                .isEqualTo(defaults.subcategorySuggestions().get(AssetCategory.INVESTMENT));
        assertThat(partial.subcategorySuggestions().get(AssetCategory.SAVINGS)).isEmpty();
        assertThat(partial.subcategorySuggestions().get(AssetCategory.LOAN)).isEmpty();
        assertThat(partial.subcategorySuggestions().get(AssetCategory.REAL_ESTATE)).isEmpty();
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
