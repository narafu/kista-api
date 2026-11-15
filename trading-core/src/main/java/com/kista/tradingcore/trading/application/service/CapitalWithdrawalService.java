package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.service.support.OwnedStrategy;
import com.kista.tradingcore.trading.domain.model.WithdrawCapitalCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

// 투자금 인출 진입점 — INFINITE 최소 시드 검사의 전일종가 HTTP를 트랜잭션 밖에서 하고, 소유권 재확인·잠금·쓰기는 CapitalLedgerService.withdraw 트랜잭션이 맡는다
@Service
@RequiredArgsConstructor
public class CapitalWithdrawalService {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final InfiniteMinSeedGuard minSeedGuard;
    private final CapitalLedgerService capitalLedger; // 인출 DB 변경 본문(@Transactional)

    // 진행 중인 PRIVACY/INFINITE 사이클의 장부 예수금·startAmount 차감 — 사이클 유지, 장부만 갱신(브로커 호출 없음)
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 전일종가 HTTP 포함 — 트랜잭션 밖
    public void withdraw(UUID strategyId, UUID requesterId, WithdrawCapitalCommand cmd) {
        OwnedStrategy owned = OwnedStrategy.load(strategyPort, accountPort, strategyId, requesterId);
        // HTTP 전에 싼 입력 검증부터 — 무효 요청으로 시세 조회를 낭비하지 않는다
        CapitalLedgerService.requireValidWithdrawal(owned.strategy(), cmd.amount(), owned.currency());
        BigDecimal minSeed = minSeedGuard.minSeed(owned.strategy(), owned.account().toBrokerRef()); // INFINITE 외·조회 실패는 null(검사 건너뜀)
        capitalLedger.withdraw(strategyId, requesterId, cmd, minSeed);
    }
}
