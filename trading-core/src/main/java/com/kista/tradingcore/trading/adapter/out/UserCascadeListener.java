package com.kista.tradingcore.trading.adapter.out;

import com.kista.shared.kernel.UserDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사용자 탈퇴 cascade — trading-core의 UserDeletedEvent 구독자를 이 리스너 하나로 모은다.
// 1) 계좌마다 전략 PAUSED + 증권사 미체결 취소(계좌 단건 삭제 AccountService.delete와 같은 정리, 실패는 관리자 알림 후 계속)
// 2) 한 트랜잭션에서 사이클·전략·계좌 정리 + 알림 프로필 tombstone — 실패하면 예외가 publication을 FAILED로 남기고
//    UserEventResubmitScheduler가 재발행하며 첫 실패 때 관리자에게 알린다(재기동을 기다리지 않음).
// 트랜잭션 경계는 단계마다 UserTradingDataEraser가 연다 — 리스너에 @Transactional을 두면 증권사 HTTP가 트랜잭션 안에 들어가고,
// 경계를 아예 안 열면 AFTER_COMMIT 시점에 아직 묶여 있는 재발행 트랜잭션에 쓰기가 합류해 커밋되지 않는다(UserTradingDataEraser 주석).
// 클래스 FQCN·메서드명은 EPR listener_id라 유지한다 — 바꾸면 미완료 row가 고아가 된다(docker-infra.md "배포 직전 EPR 정리 런북").
// 빈 이름 명시 — finance 모듈에도 동명 클래스(com.kista.api.finance.adapter.out.UserCascadeListener)가 있어
// 컴포넌트 스캔 기본 빈 이름('userCascadeListener')이 충돌한다.
@Component("tradingUserCascadeListener")
@RequiredArgsConstructor
public class UserCascadeListener {

    private final UserTradingDataEraser eraser; // 단계별 트랜잭션 경계를 가진 정리 빈

    // fallbackExecution — 재발행(UserEventRepublisher)은 트랜잭션 안이지만, 트랜잭션 밖 발행이 생겨도 조용히 버려지지 않게 한다
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserDeleted(UserDeletedEvent event) {
        eraser.cancelOpenOrders(event.userId());
        eraser.erase(event.userId());
    }
}
