package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.service.support.OwnedStrategy;
import com.kista.tradingcore.trading.application.usecase.WithdrawCapitalUseCase;
import com.kista.tradingcore.trading.domain.model.WithdrawCapitalCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

// 투자금 인출 진입점 — INFINITE 최소 시드 검사의 전일종가 HTTP를 트랜잭션 밖에서 하고, 소유권 재확인·잠금·쓰기는 CapitalInjectionService.withdraw 트랜잭션이 맡는다
@Service
@RequiredArgsConstructor
class CapitalWithdrawalService implements WithdrawCapitalUseCase {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final InfiniteMinSeedGuard minSeedGuard;
    private final CapitalInjectionService ledgerWriter; // 인출 DB 변경 본문(@Transactional)

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 전일종가 HTTP 포함 — 트랜잭션 밖
    public void withdraw(UUID strategyId, UUID requesterId, WithdrawCapitalCommand cmd) {
        OwnedStrategy owned = OwnedStrategy.load(strategyPort, accountPort, strategyId, requesterId);
        // HTTP 전에 싼 입력 검증부터 — 무효 요청으로 시세 조회를 낭비하지 않는다
        CapitalInjectionService.requireValidWithdrawal(owned.strategy(), cmd.amount(), owned.currency());
        BigDecimal minSeed = minSeedGuard.minSeed(owned.strategy(), owned.account().toBrokerRef()); // INFINITE 외·조회 실패는 null(검사 건너뜀)
        ledgerWriter.withdraw(strategyId, requesterId, cmd, minSeed);
    }
}
