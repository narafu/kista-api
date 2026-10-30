package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.trading.domain.creation.StrategyCreationResolver.ResolvedCreation;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.trading.application.port.output.CyclePositionInfiniteDetailPort;
import com.kista.tradingcore.trading.application.usecase.VrStrategyDetailUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

// 전략 등록 저장 5단계 원자성 — 마지막 단계 실패 시 앞선 strategy·version·cycle·position이 남지 않는지 실 DB로 검증.
// 테스트 트랜잭션을 끄고(NOT_SUPPORTED) persister의 @Transactional이 유일한 경계가 되게 한다 — 테스트 트랜잭션 안에서 실패시키면
// 어차피 테스트 종료 롤백으로 지워져 원자성을 증명하지 못한다. 커밋된 행은 @AfterEach에서 계좌 삭제(FK CASCADE)로 정리.
@Execution(ExecutionMode.SAME_THREAD)
@Import(StrategyCreationPersisterDbTest.PersisterTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StrategyCreationPersisterDbTest extends DataJpaTestBase {

    // persister + 실제 저장 어댑터 6종(package-private이라 클래스 리터럴 대신 이름 필터로 스캔)
    @TestConfiguration
    @Import(StrategyCreationPersister.class)
    @ComponentScan(basePackages = "com.kista.tradingcore.trading.adapter.out.persistence", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                    ".*\\.StrategyPersistenceAdapter", ".*\\.StrategyVersionPersistenceAdapter",
                    ".*\\.StrategyInfiniteDetailPersistenceAdapter", ".*\\.StrategyCyclePersistenceAdapter",
                    ".*\\.CyclePositionPersistenceAdapter", ".*\\.StrategyDcaDetailPersistenceAdapter"}))
    static class PersisterTestConfig {
    }

    @Autowired StrategyCreationPersister persister;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort; // 마지막 저장 단계 — 실패 주입 지점
    @MockitoBean VrStrategyDetailUseCase vrStrategyDetailUseCase; // INFINITE 경로에선 미호출

    private UUID accountId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, UUID.randomUUID(), "테스트계좌", "KIS", "74420614", "01", "key", "secret");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId); // strategy 이하 FK CASCADE
    }

    @Test
    void persist_rollsBackAllRows_whenLastStepFails() {
        doThrow(new IllegalStateException("주입된 실패")).when(cyclePositionInfiniteDetailPort).save(any());

        assertThatThrownBy(this::persistInfinite).isInstanceOf(IllegalStateException.class);

        assertThat(countStrategyRows()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM strategy_cycle sc JOIN strategy s ON s.id = sc.strategy_id WHERE s.account_id = ?",
                Integer.class, accountId)).isZero();
    }

    @Test
    void persist_commitsAllRows_whenEveryStepSucceeds() {
        persistInfinite();

        assertThat(countStrategyRows()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cycle_position cp JOIN strategy_cycle sc ON sc.id = cp.strategy_cycle_id JOIN strategy s ON s.id = sc.strategy_id WHERE s.account_id = ?",
                Integer.class, accountId)).isEqualTo(1);
    }

    @Test
    void persist_dca_savesDcaRuleRowAndZeroPosition() {
        var config = com.kista.tradingcore.support.TradingFixtures.dcaConfig(com.kista.tradingcore.support.TradingFixtures.stockDcaRule(
                "11111111-1111-1111-1111-111111111111", com.kista.shared.kernel.OrderDirection.BUY, "300", com.kista.shared.kernel.OrderType.MOC));

        persister.persist(new StrategyCreationPersister.Draft(accountId, StrategyType.DCA, StrategyCycleSeedType.NONE,
                new ResolvedCreation(StrategyTicker.UPRO, 20, null, null, null), null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, BigDecimal.ZERO, null, LocalDate.now(), config));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM strategy_dca_rule dv JOIN strategy_version sv ON sv.id = dv.strategy_version_id JOIN strategy s ON s.id = sv.strategy_id WHERE s.account_id = ?",
                Integer.class, accountId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cycle_position cp JOIN strategy_cycle sc ON sc.id = cp.strategy_cycle_id JOIN strategy s ON s.id = sc.strategy_id WHERE s.account_id = ?",
                Integer.class, accountId)).isEqualTo(1);
    }

    private void persistInfinite() {
        persister.persist(new StrategyCreationPersister.Draft(accountId, StrategyType.INFINITE, StrategyCycleSeedType.NONE,
                new ResolvedCreation(StrategyTicker.SOXL, 20, null, null, null), null,
                new BigDecimal("1000"), BigDecimal.ZERO, null, null, new BigDecimal("1000"), null, LocalDate.now(), null));
    }

    private Integer countStrategyRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM strategy WHERE account_id = ?", Integer.class, accountId);
    }
}
