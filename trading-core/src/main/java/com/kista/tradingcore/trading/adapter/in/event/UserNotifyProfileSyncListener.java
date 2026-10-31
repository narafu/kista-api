package com.kista.tradingcore.trading.adapter.in.event;

import com.kista.shared.kernel.UserNotifyProfileChangedEvent;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// trading.user_notify_profile 복제본 동기화 — user 모듈이 발행한 프로필 변경 이벤트를 받아 upsert 한다(탈퇴는 UserTradingDataEraseService가 tombstone 처리).
// root가 Redis Stream으로 보낸 이벤트를 UserEventStreamBridge가 로컬 재발행한다 — 실패하면 publication이 FAILED로 남아 UserEventResubmitScheduler가 재발행.
// 버전 가드(occurredAt)·upsert는 TradingUserProfilePort.applyChange(UserNotifyProfilePersistenceAdapter)가 맡는다.
// fallbackExecution=true는 안전망이다 — 현재 발행 지점은 전부 트랜잭션 안이지만(login()의 ADMIN
// 승격도 AdminSeedPromoter로 분리됨), 누군가 트랜잭션 밖에서 발행을 추가하면 이 플래그가 없을 때
// 이벤트가 조용히 버려진다. 켜두면 대신 인라인 동기 실행이라 실패가 발행자까지 전파된다 —
// 복제본 동기화는 조용한 유실보다 시끄러운 실패가 낫다는 판단.
// 클래스 FQCN·메서드명은 EPR listener_id다 — 옮기면 미완료 row가 고아가 된다(docker-infra.md "배포 직전 EPR 정리 런북").
@Component
@RequiredArgsConstructor
class UserNotifyProfileSyncListener {

    private final TradingUserProfilePort tradingUserProfilePort; // 복제본 upsert

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onProfileChanged(UserNotifyProfileChangedEvent event) {
        tradingUserProfilePort.applyChange(event);
    }
}
