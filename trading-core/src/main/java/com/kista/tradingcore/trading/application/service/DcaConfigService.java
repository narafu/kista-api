package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.StrategyVersionPort;
import com.kista.tradingcore.trading.application.usecase.DcaConfigUseCase;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

// DCA 설정 수정 — 기존 사이클·주문의 strategy_version_id는 옛(soft-deleted) 버전을 그대로 가리키며, 실행 경로는 활성 버전을 직접 읽어 영향이 없다
@Service
@RequiredArgsConstructor
class DcaConfigService implements DcaConfigUseCase {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final StrategyVersionPort strategyVersionPort;
    private final StrategyDcaDetailPort dcaDetailPort;

    @Override
    @Transactional
    public void updateConfig(UUID strategyId, UUID requesterId, DcaConfig config) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        if (!strategy.isDca()) throw new IllegalArgumentException("DCA 전략이 아닙니다: " + strategyId);
        DcaConfig current = dcaDetailPort.findActiveByStrategyId(strategyId)
                .orElseThrow(() -> new IllegalStateException("DCA 설정이 없습니다: " + strategyId));
        if (current.direction() != config.direction()) throw new IllegalArgumentException("DCA 방향은 변경할 수 없습니다");
        // nextVersionNo는 soft delete 전에 — @SQLRestriction이 삭제 버전을 가린다 (CycleSnapshotCreator.reconfigureVrCycle과 동일 순서)
        int nextVersionNo = strategyVersionPort.nextVersionNo(strategyId);
        strategyVersionPort.softDeleteActiveByStrategyId(strategyId, Instant.now());
        StrategyVersion version = strategyVersionPort.save(new StrategyVersion(null, strategyId, nextVersionNo, null, null));
        dcaDetailPort.save(version.id(), config);
    }
}
