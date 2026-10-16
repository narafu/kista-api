package com.kista.api.web;

import com.kista.api.notify.application.port.output.NotifyPort;
import com.kista.shared.kernel.UserDeletedEvent;
import com.kista.shared.kernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

// trading-core 복제본 동기화용 사용자 이벤트(user.deleted·notify-profile.changed) Redis 발행이 실패해 FAILED로 남은
// public.event_publication을 주기적으로 재발행한다 — Modulith 기본 재발행은 재기동 시 1회뿐이라, 그사이 삭제된 사용자가
// trading-core에서 계속 매매되는 것을 막는다. 처음 실패를 발견한 회차에만 관리자에게 알린다(상태 없이 1회 — completionAttempts는
// 재발행할 때마다 1씩 늘고 첫 실패 시 1 — 재기동 재발행이 먼저 집어 횟수를 올린 건은 알림이 빠질 수 있다).
// 같은 이벤트의 다른 root 리스너(finance cascade·FCM 정리·탈퇴 알림) 실패분도 함께 재발행된다(각자 멱등). user 모듈에 두지 않는 이유: 알림(notify)이 이미 user에 의존해 순환이 생긴다.
// kista-scheduler(public EPR 재발행 소유)에서만 돈다 — kista-api role은 scheduler.enabled=false
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)
public class UserEventResubmitScheduler {

    private static final Duration MIN_AGE = Duration.ofMinutes(5); // 상태 NULL 레거시 행만 거른다 — Modulith 2.1.1 SQL은 OR 우선순위상 FAILED 행엔 적용되지 않는다
    private static final int BATCH_SIZE = 10_000; // Modulith가 FAILED 행을 오래된 순으로 이만큼 읽은 뒤 필터를 적용한다 — 작으면 다른 이벤트 실패분에 막혀 사용자 이벤트에 닿지 못한다

    private final FailedEventPublications failedEventPublications; // Modulith 실패 publication 재발행 API
    private final NotifyPort notifyPort;                            // 관리자 텔레그램 알림

    // 10분마다 실패한 사용자 이벤트만 재발행 — 다른 리스너의 실패분은 건드리지 않는다
    @Scheduled(fixedDelay = 10, initialDelay = 10, timeUnit = TimeUnit.MINUTES)
    void resubmitFailedUserEvents() {
        failedEventPublications.resubmit(ResubmissionOptions.defaults()
                .withMinAge(MIN_AGE)
                .withBatchSize(BATCH_SIZE)
                .withFilter(this::isUserSyncEventAndAlertOnFirstFailure));
    }

    // 재발행 대상 판정 — 사용자 동기화 이벤트면 true, 첫 실패 발견 회차면 관리자 알림도 보낸다
    boolean isUserSyncEventAndAlertOnFirstFailure(EventPublication publication) {
        Object event = publication.getEvent();
        if (!(event instanceof UserDeletedEvent || event instanceof UserNotifyProfileChangedEvent)) {
            return false;
        }
        if (publication.getCompletionAttempts() <= 1) {
            log.error("사용자 이벤트 trading-core 전달 실패 — 재발행 시작: publicationId={}, event={}",
                    publication.getIdentifier(), event.getClass().getSimpleName());
            notifyPort.notifyError(new IllegalStateException(
                    "사용자 이벤트(" + event.getClass().getSimpleName() + ") 처리 실패 — 10분마다 재발행합니다. "
                            + "trading-core 전달이면 Redis 상태를 확인하세요. publicationId=" + publication.getIdentifier()));
        }
        return true;
    }
}
