package com.kista.adapter.out.persistence.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.account.Account.Broker;
import com.kista.domain.model.settings.RuntimeSettings;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class RuntimeSettingsPersistenceAdapterIT extends DataJpaTestBase {

    @Autowired RuntimeSettingsJpaRepository repository; // 실제 PostgreSQL 저장소

    @Test
    void seededSettingsLoadAndSavedSettingsRoundTrip() {
        RuntimeSettingsPersistenceAdapter adapter = new RuntimeSettingsPersistenceAdapter(repository, new ObjectMapper());
        RuntimeSettings seeded = adapter.load();

        assertThat(seeded).isEqualTo(RuntimeSettings.defaults());

        // 증권사 한 곳을 비활성화한 설정을 저장한 뒤 다시 조회한다.
        Map<Broker, RuntimeSettings.BrokerSettings> brokers = new EnumMap<>(seeded.brokers());
        brokers.put(Broker.TOSS, new RuntimeSettings.BrokerSettings(false));
        RuntimeSettings changed = new RuntimeSettings(
                seeded.approvalRequired(), brokers, seeded.strategies());

        adapter.save(changed);
        assertThat(adapter.load()).isEqualTo(changed);
    }
}
