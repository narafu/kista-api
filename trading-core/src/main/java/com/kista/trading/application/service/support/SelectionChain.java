package com.kista.trading.application.service.support;

import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.StrategyPort;

import java.util.UUID;

// 관리자 작업 대상 선택 체인 검증 — account→strategy→order 소속 관계 확인.
// user는 신원 대조(UUID 비교)만 필요해 User 객체 전체를 들고 있지 않는다 —
// 호출자(admin, api에 남음)가 user 존재 자체는 자기 쪽에서 이미 검증했다는 전제.
public final class SelectionChain {

    private SelectionChain() {}

    public record Selection(Account account, Strategy strategy) {}

    public static Selection resolveAndValidate(AccountPort accountPort, StrategyPort strategyPort,
                                                UUID accountId, UUID strategyId, UUID userId) {
        Account account = accountPort.findByIdOrThrow(accountId);
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        validate(userId, account, strategy);
        return new Selection(account, strategy);
    }

    public static void validate(UUID userId, Account account, Strategy strategy) {
        if (!account.userId().equals(userId)) {
            throw new IllegalArgumentException("account가 user에 속하지 않습니다");
        }
        if (!strategy.accountId().equals(account.id())) {
            throw new IllegalArgumentException("strategy가 account에 속하지 않습니다");
        }
    }

    public static void validate(UUID userId, Account account, Strategy strategy, Order order) {
        validate(userId, account, strategy);
        if (!order.accountId().equals(account.id())) {
            throw new IllegalArgumentException("order가 account에 속하지 않습니다");
        }
    }
}
