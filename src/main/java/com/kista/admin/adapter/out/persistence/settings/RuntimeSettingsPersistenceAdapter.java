package com.kista.admin.adapter.out.persistence.settings;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.kista.admin.domain.model.RuntimeSettings;
import com.kista.admin.application.port.output.RuntimeSettingsPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class RuntimeSettingsPersistenceAdapter implements RuntimeSettingsPort {

    static final String SETTING_KEY = "runtime"; // 단일 런타임 설정 행 키

    private final RuntimeSettingsJpaRepository repository; // 설정 JPA 저장소
    private final ObjectMapper objectMapper; // JSON 직렬화 경계

    @Override
    public RuntimeSettings load() {
        // 행이 아직 없으면 운영 동작을 보존하는 안전 기본값을 반환한다.
        return repository.findById(SETTING_KEY)
                .map(RuntimeSettingsEntity::getSettingValue)
                .map(this::deserialize)
                .orElseGet(RuntimeSettings::defaults);
    }

    @Override
    public RuntimeSettings loadForUpdate() {
        // 누락 행을 원자적으로 복구한 뒤 가입 결정과 관리자 변경이 같은 잠금을 공유한다.
        repository.insertIfMissing(SETTING_KEY, serialize(RuntimeSettings.defaults()));
        return repository.findBySettingKeyForUpdate(SETTING_KEY)
                .map(RuntimeSettingsEntity::getSettingValue)
                .map(this::deserialize)
                .orElseThrow(() -> new IllegalStateException("runtime settings row missing after initialization"));
    }

    @Override
    public RuntimeSettings save(RuntimeSettings settings) {
        // 검증된 도메인 설정만 JSON 행으로 직렬화한다.
        repository.save(new RuntimeSettingsEntity(SETTING_KEY, serialize(settings)));
        return settings;
    }

    private String serialize(RuntimeSettings settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("runtime settings serialization failed", e);
        }
    }

    private RuntimeSettings deserialize(String json) {
        try {
            return objectMapper.treeToValue(dropLegacyTradingSections(objectMapper.readTree(json)), RuntimeSettings.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalStateException("runtime settings deserialization failed", e);
        }
    }

    // brokers/strategies 섹션은 trading-core 소유(trading.trading_runtime_settings)로 이동했다 — 이동 이전에 저장된
    // 행에 남아 있는 두 키는 무시한다(다음 save()에서 자연히 사라진다). benchmarks 누락은 RuntimeSettings 생성자가 보충한다.
    private static JsonNode dropLegacyTradingSections(JsonNode root) {
        if (root instanceof ObjectNode node) {
            node.remove("brokers");
            node.remove("strategies");
        }
        return root;
    }
}
