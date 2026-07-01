package com.kista.application.service.admin;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;

// 관리자 작업 대상 선택 체인 검증 — user→account→strategy→order 소속 관계 확인
final class AdminSelectionChain {

    private AdminSelectionChain() {}

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
