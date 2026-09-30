package com.kista.trading.adapter.out.persistence.policy;

import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.TradingPolicySettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingPolicySettingsPersistenceAdapterTest {

    @Mock TradingPolicySettingsJpaRepository repository; // 영속 저장소 대역

    private TradingPolicySettingsPersistenceAdapter adapter; // 테스트 대상
    private ObjectMapper objectMapper; // 저장 JSON 생성기

    @BeforeEach
    void setUp() {
        // Boot 자동설정 ObjectMapper와 동일하게 미지 필드를 무시하도록 맞춘다(root RuntimeSettingsPersistenceAdapterTest와 동일 근거)
        objectMapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        adapter = new TradingPolicySettingsPersistenceAdapter(repository, objectMapper);
    }

    @Test
    void loadReturnsSafeDefaultsWhenRowIsMissing() {
        when(repository.findById(TradingPolicySettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.empty());

        assertThat(adapter.load()).isEqualTo(TradingPolicySettings.defaults());
    }

    @Test
    void saveSerializesWholePolicyIntoSingletonRow() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TradingPolicySettings saved = adapter.save(TradingPolicySettings.defaults());

        assertThat(saved).isEqualTo(TradingPolicySettings.defaults());
        verify(repository).save(argThat(entity ->
                entity.getSettingKey().equals(TradingPolicySettingsPersistenceAdapter.SETTING_KEY)
                        && entity.getSettingValue().contains("\"brokers\"")
                        && entity.getSettingValue().contains("\"strategies\"")));
    }

    @Test
    void loadDeserializesStoredJsonIntoPolicy() throws Exception {
        TradingPolicySettings expected = TradingPolicySettings.defaults();
        when(repository.findById(TradingPolicySettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.of(
                new TradingPolicySettingsEntity(TradingPolicySettingsPersistenceAdapter.SETTING_KEY, objectMapper.writeValueAsString(expected))));

        assertThat(adapter.load()).isEqualTo(expected);
    }

    @Test
    @DisplayName("brokers 맵에 신규 enum 키(MOCK)가 없는 저장된 JSON도 defaults로 보충되어 로드된다")
    void loadBackfillsMissingBrokerEnumKey() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.valueToTree(TradingPolicySettings.defaults());
        ((ObjectNode) root.get("brokers")).remove("MOCK");
        when(repository.findById(TradingPolicySettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.of(
                new TradingPolicySettingsEntity(TradingPolicySettingsPersistenceAdapter.SETTING_KEY, objectMapper.writeValueAsString(root))));

        TradingPolicySettings loaded = adapter.load();

        assertThat(loaded.brokers()).containsKey(Broker.MOCK);
        assertThat(loaded.brokerEnabled(Broker.MOCK)).isTrue();
    }
}
