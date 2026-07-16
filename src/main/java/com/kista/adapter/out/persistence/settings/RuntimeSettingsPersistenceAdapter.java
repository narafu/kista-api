package com.kista.adapter.out.persistence.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.domain.port.out.RuntimeSettingsPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

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
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("runtime settings serialization failed", e);
        }
    }

    private RuntimeSettings deserialize(String json) {
        try {
            JsonNode root = backfillMissingEnumKeys(objectMapper.readTree(json));
            return objectMapper.treeToValue(root, RuntimeSettings.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("runtime settings deserialization failed", e);
        }
    }

    // DB 행 저장 이후 Broker/Strategy.Type에 신규 enum 상수가 추가된 경우, 관리자가 아직 값을
    // 채워넣지 않은 새 키를 defaults()로 보충해 전체 앱 장애(누락 키 검증 실패) 대신 안전하게 로드되게 한다.
    private JsonNode backfillMissingEnumKeys(JsonNode root) {
        JsonNode defaultsNode = objectMapper.valueToTree(RuntimeSettings.defaults());
        backfillSection((ObjectNode) root, defaultsNode, "brokers");
        backfillSection((ObjectNode) root, defaultsNode, "strategies");
        return root;
    }

    private void backfillSection(ObjectNode root, JsonNode defaultsNode, String section) {
        ObjectNode sectionNode = (ObjectNode) root.get(section);
        ObjectNode defaultsSection = (ObjectNode) defaultsNode.get(section);
        Iterator<Map.Entry<String, JsonNode>> defaultFields = defaultsSection.fields();
        while (defaultFields.hasNext()) {
            Map.Entry<String, JsonNode> entry = defaultFields.next();
            if (!sectionNode.has(entry.getKey())) {
                sectionNode.set(entry.getKey(), entry.getValue());
            }
        }
    }
}
