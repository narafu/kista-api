package com.kista.trading.adapter.out.persistence.policy;

import com.kista.sharedkernel.TradingPolicySettings;
import com.kista.trading.application.port.output.TradingPolicySettingsPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class TradingPolicySettingsPersistenceAdapter implements TradingPolicySettingsPort {

    static final String SETTING_KEY = "trading-policy"; // 단일 정책 행 키

    private final TradingPolicySettingsJpaRepository repository; // 정책 JPA 저장소
    private final ObjectMapper objectMapper; // JSON 직렬화 경계

    @Override
    public TradingPolicySettings load() {
        // 행이 아직 없으면 운영 동작을 보존하는 안전 기본값을 반환한다.
        return repository.findById(SETTING_KEY)
                .map(TradingPolicySettingsEntity::getSettingValue)
                .map(this::deserialize)
                .orElseGet(TradingPolicySettings::defaults);
    }

    @Override
    public TradingPolicySettings save(TradingPolicySettings settings) {
        // 검증된 정책만 JSON 행으로 직렬화한다 — 할당식 @Id라 save()가 upsert
        repository.save(new TradingPolicySettingsEntity(SETTING_KEY, serialize(settings)));
        return settings;
    }

    private String serialize(TradingPolicySettings settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("trading policy serialization failed", e);
        }
    }

    private TradingPolicySettings deserialize(String json) {
        try {
            JsonNode root = backfillMissingEnumKeys(objectMapper.readTree(json));
            return objectMapper.treeToValue(root, TradingPolicySettings.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalStateException("trading policy deserialization failed", e);
        }
    }

    // DB 행 저장 이후 Broker/StrategyType에 신규 enum 상수가 추가된 경우, 관리자가 아직 값을
    // 채워넣지 않은 새 키를 defaults()로 보충해 전체 앱 장애(누락 키 검증 실패) 대신 안전하게 로드되게 한다.
    private JsonNode backfillMissingEnumKeys(JsonNode root) {
        JsonNode defaultsNode = objectMapper.valueToTree(TradingPolicySettings.defaults());
        backfillSection((ObjectNode) root, defaultsNode, "brokers");
        backfillSection((ObjectNode) root, defaultsNode, "strategies");
        return root;
    }

    private void backfillSection(ObjectNode root, JsonNode defaultsNode, String section) {
        ObjectNode sectionNode = (ObjectNode) root.get(section);
        ObjectNode defaultsSection = (ObjectNode) defaultsNode.get(section);
        Iterator<Map.Entry<String, JsonNode>> defaultFields = defaultsSection.properties().iterator();
        while (defaultFields.hasNext()) {
            Map.Entry<String, JsonNode> entry = defaultFields.next();
            if (!sectionNode.has(entry.getKey())) {
                sectionNode.set(entry.getKey(), entry.getValue());
            }
        }
    }
}
