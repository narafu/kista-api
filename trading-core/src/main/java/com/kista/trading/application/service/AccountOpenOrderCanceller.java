package com.kista.trading.application.service;

import com.kista.account.application.port.output.AccountOpenOrderCancelPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.Strategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// account 정의 포트 구현 — 계좌의 전략마다 PAUSED 기록 후 현재 사이클 미체결 주문을 정리하고, 재시도로 풀릴 수 있는 취소 실패 건수를 합산한다
// 자격증명 오류(키 만료·철회) 실패는 재시도해도 풀리지 않으므로 삭제를 막지 않는다
// 비-트랜잭션 — 증권사 취소 HTTP는 OrderCancelService가 트랜잭션 밖에서 수행
@Service
@RequiredArgsConstructor
class AccountOpenOrderCanceller implements AccountOpenOrderCancelPort {

    private final StrategyPort strategyPort;
    private final OrderCancelService orderCancelService;
    private final StrategyStateWriter stateWriter; // 취소 전 PAUSED 기록 — 이후 배치가 새 주문을 만들지 않도록

    @Override
    public int cancelOpenOrders(Account account) {
        int failed = 0;
        for (Strategy strategy : strategyPort.findByAccountId(account.id())) {
            stateWriter.pause(strategy.id());
            failed += orderCancelService.cancelOpenOrders(strategy.id(), account).retryableFailedCount();
        }
        return failed;
    }
}
