package com.kista.trading.adapter.out.persistence.policy;

import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.BrokerSettings;
import com.kista.sharedkernel.TradingPolicySettings;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 로컬 PostgreSQL(kistadb_test) 필요 — docker compose up -d postgres 선행
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class TradingPolicySettingsPersistenceAdapterIT extends DataJpaTestBase {

    @Autowired TradingPolicySettingsJpaRepository repository; // 실제 PostgreSQL 저장소

    @Test
    void missingRowLoadsDefaultsAndSavedSettingsRoundTrip() {
        TradingPolicySettingsPersistenceAdapter adapter = new TradingPolicySettingsPersistenceAdapter(repository, new ObjectMapper());

        assertThat(adapter.load()).isEqualTo(TradingPolicySettings.defaults());

        // 증권사 한 곳을 비활성화한 정책을 저장한 뒤 다시 조회한다.
        Map<Broker, BrokerSettings> brokers = new EnumMap<>(TradingPolicySettings.defaults().brokers());
        brokers.put(Broker.TOSS, new BrokerSettings(false));
        TradingPolicySettings changed = new TradingPolicySettings(brokers, TradingPolicySettings.defaults().strategies());

        adapter.save(changed);

        assertThat(adapter.load()).isEqualTo(changed);
        assertThat(adapter.load().brokerEnabled(Broker.TOSS)).isFalse();
    }
}
