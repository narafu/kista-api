package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.*;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.StrategyVersionPort;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.kista.tradingcore.support.TradingFixtures.cryptoDcaRule;
import static com.kista.tradingcore.support.TradingFixtures.dcaConfig;
import static com.kista.tradingcore.support.TradingFixtures.stockDcaRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DcaConfigServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock AccountPort accountPort;
    @Mock StrategyVersionPort strategyVersionPort;
    @Mock StrategyDcaDetailPort dcaDetailPort;

    DcaConfigService service;

    static final UUID STRATEGY_ID = UUID.randomUUID();
    static final UUID ACCOUNT_ID = UUID.randomUUID();
    static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DcaConfigService(strategyPort, accountPort, strategyVersionPort, dcaDetailPort);
        // 시장 규칙 검증용 계좌 — 기존 테스트는 주식(KIS) 계좌
        lenient().when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID))
                .thenReturn(com.kista.tradingcore.support.TradingFixtures.kisAccount(ACCOUNT_ID, USER_ID));
    }

    static final String KEY = "11111111-1111-1111-1111-111111111111";

    private static DcaStrategyConfig config(String ruleKey, OrderDirection direction, String amount) {
        return dcaConfig(stockDcaRule(ruleKey, direction, amount, OrderType.MOC));
    }

    private static Strategy strategy(StrategyType type) {
        return new Strategy(STRATEGY_ID, ACCOUNT_ID, type, StrategyStatus.ACTIVE, StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
    }

    // 새 버전 생성 공통 stub
    private UUID stubNewVersion() {
        UUID newVersionId = UUID.randomUUID();
        when(strategyVersionPort.nextVersionNo(STRATEGY_ID)).thenReturn(2);
        when(strategyVersionPort.save(any())).thenReturn(new StrategyVersion(newVersionId, STRATEGY_ID, 2, null, null));
        return newVersionId;
    }

    @Test
    void updateConfig_createsNewVersionAndDetail() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy(StrategyType.DCA));
        when(dcaDetailPort.findActiveByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(config(KEY, OrderDirection.BUY, "300")));
        UUID newVersionId = stubNewVersion();

        service.updateConfig(STRATEGY_ID, USER_ID, config(KEY, OrderDirection.BUY, "500"));

        // 버전 번호 조회는 soft delete 이전이어야 한다
        InOrder order = inOrder(strategyVersionPort, dcaDetailPort);
        order.verify(strategyVersionPort).nextVersionNo(STRATEGY_ID);
        order.verify(strategyVersionPort).softDeleteActiveByStrategyId(eq(STRATEGY_ID), any());
        order.verify(dcaDetailPort).save(newVersionId, config(KEY, OrderDirection.BUY, "500"));
    }

    @Test
    void ruleKey_보존_수정은_같은_키를_유지한다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy(StrategyType.DCA));
        when(dcaDetailPort.findActiveByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(config(KEY, OrderDirection.BUY, "300")));
        UUID newVersionId = stubNewVersion();

        // 방향 변경도 허용 — 규칙 단위 수정
        service.updateConfig(STRATEGY_ID, USER_ID, config(KEY, OrderDirection.SELL, "500"));

        ArgumentCaptor<DcaStrategyConfig> saved = ArgumentCaptor.forClass(DcaStrategyConfig.class);
        verify(dcaDetailPort).save(eq(newVersionId), saved.capture());
        assertThat(saved.getValue().rules()).singleElement().satisfies(r -> {
            assertThat(r.ruleKey()).isEqualTo(KEY);
            assertThat(r.direction()).isEqualTo(OrderDirection.SELL);
        });
    }

    @Test
    void 신규_규칙은_UUID_ruleKey를_받는다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy(StrategyType.DCA));
        when(dcaDetailPort.findActiveByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(config(KEY, OrderDirection.BUY, "300")));
        UUID newVersionId = stubNewVersion();

        service.updateConfig(STRATEGY_ID, USER_ID, dcaConfig(
                stockDcaRule(KEY, OrderDirection.BUY, "300", OrderType.MOC),
                stockDcaRule(null, OrderDirection.SELL, "200", OrderType.MOO)));

        ArgumentCaptor<DcaStrategyConfig> saved = ArgumentCaptor.forClass(DcaStrategyConfig.class);
        verify(dcaDetailPort).save(eq(newVersionId), saved.capture());
        List<DcaRule> rules = saved.getValue().rules();
        assertThat(rules.get(0).ruleKey()).isEqualTo(KEY);
        assertThat(UUID.fromString(rules.get(1).ruleKey())).isNotNull(); // UUID 문자열(36자)
        assertThat(rules.get(1).ruleKey()).hasSize(36).isNotEqualTo(KEY);
    }

    @Test
    void 없는_ruleKey는_400() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy(StrategyType.DCA));
        when(dcaDetailPort.findActiveByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(config(KEY, OrderDirection.BUY, "300")));

        assertThatThrownBy(() -> service.updateConfig(STRATEGY_ID, USER_ID,
                config("22222222-2222-2222-2222-222222222222", OrderDirection.BUY, "300")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("존재하지 않는 규칙입니다");
        verify(strategyVersionPort, never()).softDeleteActiveByStrategyId(any(), any());
    }

    @Test
    void updateConfig_rejectsNonDcaStrategy() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy(StrategyType.INFINITE));

        assertThatThrownBy(() -> service.updateConfig(STRATEGY_ID, USER_ID, config(KEY, OrderDirection.BUY, "300")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(dcaDetailPort);
    }

    @Test
    void updateConfig_stockAccount_rejectsMarketOrderType() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy(StrategyType.DCA));
        DcaStrategyConfig crypto = dcaConfig(cryptoDcaRule(KEY, OrderDirection.BUY, "10000", 9));

        assertThatThrownBy(() -> service.updateConfig(STRATEGY_ID, USER_ID, crypto))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MOO 또는 MOC");
        verify(strategyVersionPort, never()).save(any());
    }
}
