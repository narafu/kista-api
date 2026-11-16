package com.kista.tradingcore.trading.adapter.out.persistence.policy;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.TradingPolicySettings;
import com.kista.shared.support.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V23(코인 삭제) SQL을 실 DB에서 다시 실행해 운영 정책 JSON이 새 코드로 읽히는지, 코인 행이 지워지는지, 가드가 막는지 확인한다
@Execution(ExecutionMode.SAME_THREAD)
class V23CryptoRemovalMigrationDbTest extends DataJpaTestBase {

    private static final Path V23 = Path.of("trading-core/src/main/resources/db/migration-trading/V23__remove_crypto_accounts.sql"); // workingDir=루트

    // 운영 행 전문(2026-11-16 조회) — KIS 키는 이미 없고 DCA 허용 종목에 BITO·EETH가 있다
    private static final String PROD_POLICY = """
            {"brokers": {"MOCK": {"enabled": true, "allowedIp": null}, "TOSS": {"enabled": true, "allowedIp": null}, "UPBIT": {"enabled": false, "allowedIp": "168.107.63.152"}, "MERITZ": {"enabled": true, "allowedIp": null}, "BINANCE": {"enabled": true, "allowedIp": "168.107.63.152"}}, "strategies": {"VR": {"ticker": {"customizable": false, "defaultValue": "TQQQ", "allowedValues": ["TQQQ"]}, "enabled": true, "bandWidth": {"customizable": true, "defaultValue": 15, "allowedValues": [10, 15, 20]}, "divisionCount": null, "intervalWeeks": {"customizable": true, "defaultValue": 2, "allowedValues": [1, 2, 4]}, "recurringMode": {"customizable": true, "defaultValue": "DEPOSIT", "allowedValues": ["DEPOSIT", "HOLD", "WITHDRAW"]}}, "DCA": {"ticker": {"customizable": true, "defaultValue": "QLD", "allowedValues": ["UPRO", "QLD", "BITO", "EETH"]}, "enabled": true, "bandWidth": null, "divisionCount": null, "intervalWeeks": null, "recurringMode": null}, "PRIVACY": {"ticker": {"customizable": false, "defaultValue": "SOXL", "allowedValues": ["SOXL"]}, "enabled": true, "bandWidth": null, "divisionCount": null, "intervalWeeks": null, "recurringMode": null}, "INFINITE": {"ticker": {"customizable": true, "defaultValue": "USD", "allowedValues": ["MAGX", "TQQQ", "USD", "SOXL"]}, "enabled": true, "bandWidth": null, "divisionCount": {"customizable": true, "defaultValue": 20, "allowedValues": [20, 30, 40]}, "intervalWeeks": null, "recurringMode": null}}}
            """;
    // findings/30 0절 시점 모양 — KIS 키와 DCA 허용 종목의 BTC·ETH가 남아 있다
    private static final String OLDER_POLICY = PROD_POLICY
            .replace("\"MOCK\": {", "\"KIS\": {\"enabled\": false, \"allowedIp\": null}, \"MOCK\": {")
            .replace("[\"UPRO\", \"QLD\", \"BITO\", \"EETH\"]", "[\"UPRO\", \"QLD\", \"BTC\", \"ETH\"]");

    @Autowired JdbcTemplate jdbc;
    @Autowired TradingPolicySettingsJpaRepository repository;

    @Test
    void 운영_정책_JSON은_V23_뒤_새_코드로_역직렬화된다() throws IOException {
        TradingPolicySettings loaded = loadAfterV23(PROD_POLICY);

        assertThat(loaded.brokers().keySet()).containsExactlyInAnyOrder(Broker.values());
        assertThat(loaded.brokerEnabled(Broker.MERITZ)).isTrue();
        assertThat(loaded.strategy(StrategyType.DCA).ticker().allowedValues())
                .containsExactly(StrategyTicker.UPRO, StrategyTicker.QLD, StrategyTicker.BITO, StrategyTicker.EETH);
        assertThat(jdbc.queryForObject("SELECT setting_value::text FROM trading.trading_runtime_settings WHERE setting_key = 'trading-policy'", String.class))
                .doesNotContain("UPBIT", "BINANCE", "allowedIp");
    }

    @Test
    void KIS_키와_BTC_ETH가_남은_옛_정책도_V23이_정리한다() throws IOException {
        TradingPolicySettings loaded = loadAfterV23(OLDER_POLICY);

        assertThat(loaded.strategy(StrategyType.DCA).ticker().allowedValues())
                .containsExactly(StrategyTicker.UPRO, StrategyTicker.QLD);
        assertThat(jdbc.queryForObject("SELECT setting_value::text FROM trading.trading_runtime_settings WHERE setting_key = 'trading-policy'", String.class))
                .doesNotContain("KIS", "BTC", "ETH");
    }

    @Test
    void 삭제_표시된_코인_계좌와_종속_행을_지우고_주식_계좌는_남긴다() throws IOException {
        UUID crypto = insertAccount("UPBIT", true);
        UUID stock = insertAccount("MERITZ", false);
        UUID strategyId = insertDcaStrategy(crypto, "ETH");
        insertOrder(crypto, insertCycle(strategyId), "ETH", "MARKET");
        insertOrder(stock, insertCycle(insertDcaStrategy(stock, "QLD")), "QLD", "MOC");
        jdbc.update("INSERT INTO trading.scheduler_locks (name, lock_until, locked_at, locked_by) VALUES ('crypto-dca', now(), now(), 'test') ON CONFLICT (name) DO NOTHING");

        jdbc.execute(Files.readString(V23, StandardCharsets.UTF_8));

        assertThat(count("SELECT count(*) FROM trading.accounts WHERE id = ?", crypto)).isZero();
        assertThat(count("SELECT count(*) FROM trading.strategy WHERE id = ?", strategyId)).isZero();
        assertThat(count("SELECT count(*) FROM trading.orders WHERE account_id = ?", crypto)).isZero();
        assertThat(count("SELECT count(*) FROM trading.orders WHERE account_id = ?", stock)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading.scheduler_locks WHERE name = 'crypto-dca'", Integer.class)).isZero();
    }

    @Test
    void 살아_있는_코인_계좌가_있으면_중단한다() {
        insertAccount("BINANCE", false);

        assertThatThrownBy(() -> jdbc.execute(Files.readString(V23, StandardCharsets.UTF_8)))
                .hasMessageContaining("활성 코인 계좌");
    }

    @Test
    void 살아_있는_코인_전략이_있으면_중단한다() {
        UUID account = insertAccount("MERITZ", false);
        jdbc.update("INSERT INTO trading.strategy (id, account_id, type, ticker) VALUES (?, ?, 'DCA', 'ETH')", UUID.randomUUID(), account);

        assertThatThrownBy(() -> jdbc.execute(Files.readString(V23, StandardCharsets.UTF_8)))
                .hasMessageContaining("활성 코인 전략");
    }

    @Test
    void 주식_계좌에_코인_값이_남아_있으면_삭제_뒤_단언이_중단한다() {
        UUID stock = insertAccount("MERITZ", false);
        insertOrder(stock, insertCycle(insertDcaStrategy(stock, "QLD")), "QLD", "MARKET");

        assertThatThrownBy(() -> jdbc.execute(Files.readString(V23, StandardCharsets.UTF_8)))
                .hasMessageContaining("삭제된 코인 값");
    }

    private TradingPolicySettings loadAfterV23(String policyJson) throws IOException {
        jdbc.update("""
                INSERT INTO trading.trading_runtime_settings (setting_key, setting_value) VALUES ('trading-policy', ?::jsonb)
                ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value""", policyJson);
        jdbc.execute(Files.readString(V23, StandardCharsets.UTF_8));
        return new TradingPolicySettingsPersistenceAdapter(repository, new ObjectMapper()).load();
    }

    private UUID insertAccount(String broker, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO trading.accounts (id, user_id, broker, nickname, app_key, secret_key, deleted_at) VALUES (?, ?, ?, 'test', 'k', 's', "
                + (deleted ? "now()" : "NULL") + ")", id, UUID.randomUUID(), broker);
        return id;
    }

    private UUID insertDcaStrategy(UUID accountId, String ticker) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO trading.strategy (id, account_id, type, ticker, deleted_at) VALUES (?, ?, 'DCA', ?, now())", id, accountId, ticker);
        return id;
    }

    // 전략 버전 1 + 사이클 1 — 주문 FK 선행 행
    private UUID insertCycle(UUID strategyId) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO trading.strategy_version (id, strategy_id, version_no) VALUES (?, ?, 1)", versionId, strategyId);
        UUID cycleId = UUID.randomUUID();
        jdbc.update("INSERT INTO trading.strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, open_amount) VALUES (?, ?, ?, 0, CURRENT_DATE, 0)",
                cycleId, strategyId, versionId);
        return cycleId;
    }

    private void insertOrder(UUID accountId, UUID cycleId, String ticker, String orderType) {
        jdbc.update("""
                INSERT INTO trading.orders (id, account_id, strategy_cycle_id, trade_date, ticker, order_type, direction, quantity, price, status)
                VALUES (?, ?, ?, CURRENT_DATE, ?, ?, 'BUY', 1, 1, 'FILLED')""", UUID.randomUUID(), accountId, cycleId, ticker, orderType);
    }

    private int count(String sql, UUID id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }
}
