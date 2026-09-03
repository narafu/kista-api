package com.kista.admin.adapter.out.persistence.settings;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.kista.admin.domain.model.RuntimeSettings;
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
        // 재현하려면 여기서도 명시적으로 꺼야 한다. 끄지 않으면 loadIgnoresRemovedAssetFormOptionsFieldFromLegacyJson이
        // "실제로는 성공해야 하는데 테스트 ObjectMapper 설정 차이 때문에 실패"하는 거짓 실패가 난다.
        objectMapper = JsonMapper.builder() // Jackson 3부터 JDK8 타입(Optional 등) 기본 지원 — Jdk8Module 불필요, ObjectMapper는 불변이라 builder로 설정
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
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

        assertThat(loaded.brokers()).containsKey(com.kista.account.domain.model.Account.Broker.MOCK);
        assertThat(loaded.brokers().get(com.kista.account.domain.model.Account.Broker.MOCK).enabled()).isTrue();
    }

    @Test
    @DisplayName("assetFormOptions(운영전략 추천 목록 admin 전역 설정) 도입 이전+이후 저장분 모두 포함해 "
            + "저장된 JSON에 남은 죽은 키(assetFormOptions)도 예외 없이 로드된다 — Boot의 ObjectMapper가 "
            + "FAIL_ON_UNKNOWN_PROPERTIES를 비활성화한다는 암묵적 의존을 검증하는 회귀 테스트")
    void loadIgnoresRemovedAssetFormOptionsFieldFromLegacyJson() throws Exception {
        // UserSettings로 이관되기 전 admin 전역 설정 잔존 키를 흉내낸다 — 실제 필드는 더 이상 도메인에 없다.
        // valueToTree()로 트리 왕복시키면 BigDecimal 스케일이 미묘하게 바뀌어(10 → 10.0) equals 비교가 깨지므로
        // 순수 문자열 조작으로만 죽은 키를 주입한다.
        String defaultsJson = objectMapper.writeValueAsString(RuntimeSettings.defaults());
        String legacyJson = defaultsJson.substring(0, defaultsJson.length() - 1)
                + ",\"assetFormOptions\":{\"strategySuggestions\":[\"VR\"]}}";

        RuntimeSettingsEntity entity = new RuntimeSettingsEntity(
                RuntimeSettingsPersistenceAdapter.SETTING_KEY, legacyJson);
        when(repository.findById(RuntimeSettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.of(entity));

        RuntimeSettings loaded = adapter.load();

        assertThat(loaded).isEqualTo(RuntimeSettings.defaults());
    }
}
