package com.kista.admin.application.service;

import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.Order;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.user.domain.model.User;
import com.kista.account.application.port.output.AccountPort;
import com.kista.strategyconfig.application.port.output.StrategyPort;
import com.kista.user.application.port.output.UserPort;

import java.util.UUID;

// 관리자 작업 대상 선택 체인 검증 — user→account→strategy→order 소속 관계 확인
final class AdminSelectionChain {

    private AdminSelectionChain() {}

    // user/account/strategy 각각 조회 후 소속 관계 검증까지 한 번에 수행
    record Selection(User user, Account account, Strategy strategy) {}

    static Selection resolveAndValidate(UserPort userPort, AccountPort accountPort, StrategyPort strategyPort,
                                        UUID userId, UUID accountId, UUID strategyId) {
        User user = userPort.findByIdOrThrow(userId);
        Account account = accountPort.findByIdOrThrow(accountId);
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        validate(user, account, strategy);
        return new Selection(user, account, strategy);
    }

    // user→account→strategy 소속 관계 검증
    static void validate(User user, Account account, Strategy strategy) {
        if (!account.userId().equals(user.id())) {
            throw new IllegalArgumentException("account가 user에 속하지 않습니다");
        }
        if (!strategy.accountId().equals(account.id())) {
            throw new IllegalArgumentException("strategy가 account에 속하지 않습니다");
        }
    }

    // user→account→strategy→order 소속 관계 검증
    static void validate(User user, Account account, Strategy strategy, Order order) {
        validate(user, account, strategy);
        if (!order.accountId().equals(account.id())) {
            throw new IllegalArgumentException("order가 account에 속하지 않습니다");
        }
    }
}
