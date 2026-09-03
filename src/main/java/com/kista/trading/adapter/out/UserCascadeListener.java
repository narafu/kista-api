package com.kista.trading.adapter.out;

import com.kista.user.application.event.UserDeletedEvent;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사용자 탈퇴 cascade — trading 소유 데이터(cycle_position/strategy_cycle)를 독립적으로 soft-delete.
// UserCascadeDeleter가 직접 포트를 호출하던 것을 이벤트 구독으로 전환(user↔trading 순환 해소).
// AFTER_COMMIT 시점엔 원본 트랜잭션이 종료돼 있으므로, @Modifying soft-delete 쿼리를 위해
// REQUIRES_NEW로 새 트랜잭션을 연다(persistence adapter에 자체 @Transactional 없음).
// 빈 이름 명시 — finance 모듈에도 동명 클래스(com.kista.finance.adapter.out.UserCascadeListener)가 있어
// 컴포넌트 스캔 기본 빈 이름('userCascadeListener')이 충돌한다.
@Component("tradingUserCascadeListener")
@RequiredArgsConstructor
public class UserCascadeListener {

    private final CyclePositionPort cyclePositionPort;
    private final StrategyCyclePort strategyCyclePort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserDeleted(UserDeletedEvent event) {
        cyclePositionPort.deleteByUserId(event.userId());
        strategyCyclePort.deleteByUserId(event.userId());
    }
}
