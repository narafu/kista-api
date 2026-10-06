package com.kista.trading.adapter.out;

import com.kista.account.application.usecase.AccountUseCase;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.application.port.output.TradingUserProfilePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사용자 탈퇴 cascade — trading-core의 UserDeletedEvent 구독자를 이 리스너 하나로 모아 한 트랜잭션에서 순서대로 정리한다.
// 순서: cycle_position → strategy_cycle → strategy → accounts(+broker_tokens) → user_notify_profile.
// 중간 실패 시 전부 롤백되고 EPR이 이벤트 전체를 재시도한다(각 단계는 이미 지운 행을 건너뛰어 멱등).
// 소프트 삭제 쿼리가 accounts를 user_id로 조인하므로 계좌는 하위 데이터 정리 뒤에 지운다.
// 클래스 FQCN·메서드명은 EPR listener_id라 유지한다 — 바꾸면 미완료 row가 고아가 된다(docker-infra.md "배포 직전 EPR 정리 런북").
// 빈 이름 명시 — finance 모듈에도 동명 클래스(com.kista.finance.adapter.out.UserCascadeListener)가 있어
// 컴포넌트 스캔 기본 빈 이름('userCascadeListener')이 충돌한다.
@Component("tradingUserCascadeListener")
@RequiredArgsConstructor
public class UserCascadeListener {

    private final CyclePositionPort cyclePositionPort;
    private final StrategyCyclePort strategyCyclePort;
    private final StrategyPort strategyPort;
    private final AccountUseCase accountUseCase;               // 계좌 소프트 삭제 + 증권사 토큰 삭제 (account 모듈 소유)
    private final TradingUserProfilePort tradingUserProfilePort; // user_notify_profile 복제본 삭제

    // fallbackExecution — 재발행(UserEventRepublisher)은 트랜잭션 안이지만, 트랜잭션 밖 발행이 생겨도 조용히 버려지지 않게 한다
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserDeleted(UserDeletedEvent event) {
        cyclePositionPort.deleteByUserId(event.userId());
        strategyCyclePort.deleteByUserId(event.userId());
        strategyPort.deleteByUserId(event.userId());
        accountUseCase.deleteAllByUserId(event.userId());
        tradingUserProfilePort.deleteByUserId(event.userId());
    }
}
