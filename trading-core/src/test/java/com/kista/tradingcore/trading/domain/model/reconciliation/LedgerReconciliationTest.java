package com.kista.tradingcore.trading.domain.model.reconciliation;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.domain.model.reconciliation.LedgerReconciliation.Finding;
import com.kista.tradingcore.trading.domain.model.reconciliation.LedgerReconciliation.StrategyLedger;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.kista.shared.kernel.StrategyTicker.SOXL;
import static com.kista.shared.kernel.StrategyTicker.TQQQ;
import static org.assertj.core.api.Assertions.assertThat;

class LedgerReconciliationTest {

    private static StrategyLedger ledger(StrategyType type, StrategyTicker ticker, String deposit, String holdings) {
        return new StrategyLedger(UUID.randomUUID(), type, ticker, new BigDecimal(deposit), new BigDecimal(holdings));
    }

    @Test
    void 정상이면_빈_목록() {
        assertThat(LedgerReconciliation.check(List.of(ledger(StrategyType.INFINITE, SOXL, "100", "10")), Map.of(SOXL, 10)))
                .isEmpty();
    }

    @Test
    void 브로커가_더_많으면_무시() {
        assertThat(LedgerReconciliation.check(List.of(ledger(StrategyType.INFINITE, SOXL, "100", "10")), Map.of(SOXL, 15)))
                .isEmpty();
    }

    @Test
    void 같은_종목_전략_합산_부족() {
        List<Finding> findings = LedgerReconciliation.check(List.of(
                ledger(StrategyType.INFINITE, SOXL, "100", "6"),
                ledger(StrategyType.DCA, SOXL, "0", "4")), Map.of(SOXL, 9));

        assertThat(findings).singleElement().extracting(Finding::message).asString()
                .contains("실보유 부족: SOXL 브로커=9 < 원장합=10", "INFINITE:6", "DCA:4");
    }

    @Test
    void 같은_종목_전략_합산_일치() {
        assertThat(LedgerReconciliation.check(List.of(
                ledger(StrategyType.INFINITE, SOXL, "100", "6"),
                ledger(StrategyType.DCA, SOXL, "0", "4")), Map.of(SOXL, 10))).isEmpty();
    }

    @Test
    void 브로커에_종목이_없으면_0으로_본다() {
        assertThat(LedgerReconciliation.check(List.of(ledger(StrategyType.VR, TQQQ, "100", "3")), Map.of()))
                .singleElement().extracting(Finding::message).asString()
                .contains("브로커=0 < 원장합=3");
    }

    @Test
    void 원장_0주는_브로커_부재여도_정상() {
        assertThat(LedgerReconciliation.check(List.of(ledger(StrategyType.INFINITE, SOXL, "100", "0")), Map.of()))
                .isEmpty();
    }

    @Test
    void 소수_scale_차이로_오탐하지_않는다() {
        assertThat(LedgerReconciliation.check(List.of(ledger(StrategyType.INFINITE, SOXL, "100", "5.00000000")), Map.of(SOXL, 5)))
                .isEmpty();
    }

    @Test
    void MOCK은_보유량_대조를_건너뛴다() {
        assertThat(LedgerReconciliation.check(List.of(ledger(StrategyType.INFINITE, SOXL, "100", "10")), null))
                .isEmpty();
    }

    @Test
    void 음수_예수금은_MOCK에서도_경보() {
        assertThat(LedgerReconciliation.check(List.of(ledger(StrategyType.PRIVACY, SOXL, "-126.71", "108")), null))
                .singleElement().extracting(Finding::message).asString()
                .contains("원장 음수", "usdDeposit=-126.71");
    }

    @Test
    void 음수_holdings_경보() {
        assertThat(LedgerReconciliation.check(List.of(ledger(StrategyType.INFINITE, SOXL, "100", "-1")), Map.of(SOXL, 0)))
                .extracting(Finding::message).anyMatch(m -> m.contains("원장 음수"));
    }
}
