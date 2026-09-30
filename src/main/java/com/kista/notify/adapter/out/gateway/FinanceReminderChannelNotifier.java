package com.kista.notify.adapter.out.gateway;

import com.kista.finance.application.event.FinanceRegistrationReminderDueEvent;
import com.kista.sharedkernel.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// finance가 발행하는 가계부 등록 리마인더 이벤트를 구독한다 —
// AlertNotifier가 market/benchmark 발행자 이벤트를 구독하는 것과 같은 패턴(발행 모듈은 notify를 모른다).
// 발행 지점이 트랜잭션 밖 스케쥴러라 @TransactionalEventListener가 아닌 동기 @EventListener로 받는다.
// 이 클래스는 발행자 이벤트 → 유형·문구 매핑만 담당하고, 설정 게이트·채널 라우팅은 UserChannelNotifier가 맡는다.
@Component
@RequiredArgsConstructor
class FinanceReminderChannelNotifier {

    private final UserChannelNotifier userChannelNotifier; // 유형 무관 사용자 채널 라우팅

    @EventListener
    public void onReminderDue(FinanceRegistrationReminderDueEvent event) {
        userChannelNotifier.notify(event.userId(), NotificationType.FINANCE_REMINDER, event.title(), event.body());
    }
}
