package com.kista.account.application.port.output;

import com.kista.account.domain.model.Account;

// 계좌 삭제 전 미체결 주문 정리 — AccountService.delete()가 소비한다.
// 포트는 필요로 하는 쪽(account)이 정의하고, 주문 데이터를 가진 trading(AccountOpenOrderCanceller)이 구현한다.
public interface AccountOpenOrderCancelPort {
    // 계좌의 모든 전략 미체결 주문을 취소하고, 일시 장애로 증권사 취소에 실패한 건수를 돌려준다(자격증명 오류는 제외)
    int cancelOpenOrders(Account account);
}
