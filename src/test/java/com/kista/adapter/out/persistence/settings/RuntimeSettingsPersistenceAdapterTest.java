package com.kista.adapter.out.persistence.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.kista.domain.model.settings.RuntimeSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeSettingsPersistenceAdapterTest {

    @Mock RuntimeSettingsJpaRepository repository; // 영속 저장소 대역

    private RuntimeSettingsPersistenceAdapter adapter; // 테스트 대상
    private ObjectMapper objectMapper; // 저장 JSON 생성기

    @BeforeEach
    void setUp() {
        // Spring Boot의 자동설정 ObjectMapper는 FAIL_ON_UNKNOWN_PROPERTIES를 기본 false로 둔다 — 이 테스트는
        // 그 빈을 그대로 쓰지 않고 직접 ObjectMapper를 만들므로(Jackson 기본값은 true), 프로덕션 동작을
        // 재현하려면 여기서도 명시적으로 꺼야 한다. 끄지 않으면 loadIgnoresRemovedAssetFormOptionsFieldsFromLegacyJson이
        // "실제로는 성공해야 하는데 테스트 ObjectMapper 설정 차이 때문에 실패"하는 거짓 실패가 난다.
        objectMapper = new ObjectMapper().registerModule(new Jdk8Module())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        adapter = new RuntimeSettingsPersistenceAdapter(repository, objectMapper);
    }

    @Test
    void loadReturnsSafeDefaultsWhenRowIsMissing() {
        when(repository.findById(RuntimeSettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.empty());

        assertThat(adapter.load()).isEqualTo(RuntimeSettings.defaults());
    }

    @Test
    void saveAndLoadRoundTripTypedSettings() {
        RuntimeSettings settings = RuntimeSettings.defaults();
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        RuntimeSettings saved = adapter.save(settings);

        assertThat(saved).isEqualTo(settings);
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(entity ->
                entity.getSettingKey().equals(RuntimeSettingsPersistenceAdapter.SETTING_KEY)
                        && entity.getSettingValue().contains("approvalRequired")));
    }

    @Test
    void loadDeserializesStoredJsonIntoTypedSettings() throws Exception {
        RuntimeSettings expected = RuntimeSettings.defaults();
        RuntimeSettingsEntity entity = new RuntimeSettingsEntity(
                RuntimeSettingsPersistenceAdapter.SETTING_KEY, objectMapper.writeValueAsString(expected));
        when(repository.findById(RuntimeSettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.of(entity));

        assertThat(adapter.load()).isEqualTo(expected);
    }

    @Test
    void loadForUpdateCreatesMissingSingletonBeforeLockingIt() throws Exception {
        RuntimeSettings defaults = RuntimeSettings.defaults();
        RuntimeSettingsEntity entity = new RuntimeSettingsEntity(
                RuntimeSettingsPersistenceAdapter.SETTING_KEY, objectMapper.writeValueAsString(defaults));
        when(repository.findBySettingKeyForUpdate(RuntimeSettingsPersistenceAdapter.SETTING_KEY))
                .thenReturn(Optional.of(entity));

        assertThat(adapter.loadForUpdate()).isEqualTo(defaults);

        verify(repository).insertIfMissing(
                org.mockito.ArgumentMatchers.eq(RuntimeSettingsPersistenceAdapter.SETTING_KEY),
                org.mockito.ArgumentMatchers.contains("approvalRequired"));
        verify(repository).findBySettingKeyForUpdate(RuntimeSettingsPersistenceAdapter.SETTING_KEY);
    }

    @Test
    @DisplayName("brokers 맵에 신규 enum 키(MOCK)가 없는 저장된 JSON도 defaults로 보충되어 로드된다")
    void loadBackfillsMissingBrokerEnumKey() throws Exception {
        // MOCK 도입 이전에 저장된 것처럼 KIS/TOSS만 있는 brokers 맵을 가진 JSON을 직접 구성
        ObjectNode root = (ObjectNode) objectMapper.valueToTree(RuntimeSettings.defaults());
        ((ObjectNode) root.get("brokers")).remove("MOCK");
        String staleJson = objectMapper.writeValueAsString(root);

        RuntimeSettingsEntity entity = new RuntimeSettingsEntity(
                RuntimeSettingsPersistenceAdapter.SETTING_KEY, staleJson);
        when(repository.findById(RuntimeSettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.of(entity));

        RuntimeSettings loaded = adapter.load();

        assertThat(loaded.brokers()).containsKey(com.kista.domain.model.account.Account.Broker.MOCK);
        assertThat(loaded.brokers().get(com.kista.domain.model.account.Account.Broker.MOCK).enabled()).isTrue();
    }

    @Test
    @DisplayName("finance 스키마 도입 이전(subcategorySuggestions/institutionSuggestions/assetClassSuggestions 포함) "
            + "저장된 JSON도 예외 없이 로드되고 strategySuggestions만 복원된다 — Boot의 ObjectMapper가 "
            + "FAIL_ON_UNKNOWN_PROPERTIES를 비활성화한다는 암묵적 의존을 검증하는 회귀 테스트")
    void loadIgnoresRemovedAssetFormOptionsFieldsFromLegacyJson() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.valueToTree(RuntimeSettings.defaults());
        ObjectNode assetFormOptions = (ObjectNode) root.get("assetFormOptions");
        assetFormOptions.set("subcategorySuggestions", objectMapper.createObjectNode()
                .set("INVESTMENT", objectMapper.createArrayNode().add("연금저축펀드")));
        assetFormOptions.set("institutionSuggestions", objectMapper.createArrayNode().add("토스증권"));
        assetFormOptions.set("assetClassSuggestions", objectMapper.createArrayNode().add("미국주식"));
        String legacyJson = objectMapper.writeValueAsString(root);

        RuntimeSettingsEntity entity = new RuntimeSettingsEntity(
                RuntimeSettingsPersistenceAdapter.SETTING_KEY, legacyJson);
        when(repository.findById(RuntimeSettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.of(entity));

        RuntimeSettings loaded = adapter.load();

        assertThat(loaded.assetFormOptions().strategySuggestions())
                .isEqualTo(RuntimeSettings.defaults().assetFormOptions().strategySuggestions());
    }
}
