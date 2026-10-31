package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.account.application.port.output.AccountOpenOrderCancelPort;
import com.kista.tradingcore.account.application.usecase.AccountUseCase;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.application.usecase.UserTradingDataEraseUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// 탈퇴 사용자의 trading-core 정리 단계 2개 — TradingUserCascadeListener(AFTER_COMMIT)가 순서대로 호출한다.
// 전파 속성이 핵심이다: AFTER_COMMIT 콜백 시점엔 재발행 트랜잭션의 리소스가 아직 스레드에 묶여 있어,
// REQUIRED 쓰기는 이미 커밋된 트랜잭션에 합류해 커밋되지 않는다 — 그래서 취소 단계는 NOT_SUPPORTED로 묶인 트랜잭션을
// 떼어내고(안쪽 PAUSED·주문 상태 쓰기가 각자 새 트랜잭션을 연다), 정리 단계는 REQUIRES_NEW로 새 트랜잭션을 연다
@Slf4j
@Service
@RequiredArgsConstructor
class UserTradingDataEraseService implements UserTradingDataEraseUseCase {

    private final AccountOpenOrderCancelPort openOrderCancelPort;  // 계좌별 전략 PAUSED + 증권사 미체결 취소
    private final TradingErrorReportPort errorReportPort;          // 취소 실패 관리자 알림
    private final CyclePositionPort cyclePositionPort;
    private final StrategyCyclePort strategyCyclePort;
    private final StrategyPort strategyPort;
    private final AccountUseCase accountUseCase;                   // 계좌 목록 + 소프트 삭제·증권사 토큰 삭제 (account 모듈 소유)
    private final TradingUserProfilePort tradingUserProfilePort;   // user_notify_profile 복제본 tombstone

    // 1단계: 계좌마다 전략 PAUSED + 증권사 미체결 취소(HTTP — 트랜잭션 밖). PAUSED가 먼저 기록돼 이후 정리가 실패해도 배치가 새 주문을 내지 않는다.
    // 취소 실패는 삭제를 막지 않고 accountId로 관리자에게 알린다 — 계좌가 soft-delete되면 시스템이 다시 취소할 수 없다.
    // 계좌별 격리 — 한 계좌의 취소 예외가 다른 계좌 정리를 막지 않는다
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void cancelOpenOrders(UUID userId) {
        for (Account account : accountUseCase.listByUser(userId)) {
            int failed;
            try {
                failed = openOrderCancelPort.cancelOpenOrders(account);
            } catch (RuntimeException e) {
                log.error("탈퇴 사용자 미체결 취소 실패 userId={}, accountId={}", userId, account.id(), e);
                failed = -1;
            }
            if (failed != 0) {
                errorReportPort.reportError(new IllegalStateException(
                        "탈퇴 사용자 증권사 미체결 취소 실패 — 증권사에서 수동 취소 필요 userId=" + userId + ", accountId=" + account.id()));
            }
        }
    }

    // 2단계: 한 트랜잭션에서 순서대로 정리 — cycle_position → strategy_cycle → strategy → accounts(+broker_tokens) → user_notify_profile tombstone.
    // 소프트 삭제 쿼리가 accounts를 user_id로 조인하므로 계좌는 하위 데이터 정리 뒤에 지운다. 각 단계는 이미 지운 행을 건너뛰어 멱등
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void erase(UUID userId) {
        cyclePositionPort.deleteByUserId(userId);
        strategyCyclePort.deleteByUserId(userId);
        strategyPort.deleteByUserId(userId);
        accountUseCase.deleteAllByUserId(userId);
        tradingUserProfilePort.markDeleted(userId);
    }
}
