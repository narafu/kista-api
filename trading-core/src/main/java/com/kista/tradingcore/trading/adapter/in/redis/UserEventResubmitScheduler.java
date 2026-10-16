package com.kista.tradingcore.trading.adapter.in.redis;

import com.kista.shared.kernel.UserDeletedEvent;
import com.kista.shared.kernel.UserNotifyProfileChangedEvent;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
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

// 로컬 재발행된 사용자 이벤트의 리스너(UserCascadeListener·UserNotifyProfileSyncListener)가 실패해 FAILED로 남은
// trading.event_publication을 주기적으로 재발행한다 — AFTER_COMMIT 리스너 실패는 스트림 컨슈머까지 전파되지 않아(메시지는 ack)
// 이 잡이 유일한 재기동 전 재시도 경로다. 처음 실패를 발견한 회차에만 관리자에게 알린다(completionAttempts는 재발행마다 1씩 늘고 첫 실패 시 1).
// root의 com.kista.api.web.UserEventResubmitScheduler와 대칭(발행 측 실패 담당)
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // local에서 끄면 비활성 — 운영 kista-trading 단일 인스턴스만
class UserEventResubmitScheduler {

    private static final Duration MIN_AGE = Duration.ofMinutes(5); // 상태 NULL 레거시 행만 거른다 — Modulith 2.1.1 SQL은 OR 우선순위상 FAILED 행엔 적용되지 않는다
    private static final int BATCH_SIZE = 10_000; // Modulith가 FAILED 행을 오래된 순으로 이만큼 읽은 뒤 필터를 적용한다 — 작으면 다른 이벤트 실패분에 막혀 사용자 이벤트에 닿지 못한다

    private final FailedEventPublications failedEventPublications; // Modulith 실패 publication 재발행 API
    private final TradingErrorReportPort errorReportPort;           // 관리자 알림

    // 10분마다 실패한 사용자 이벤트만 재발행 — 매매 알림 등 다른 리스너의 실패분은 건드리지 않는다
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
            log.error("사용자 이벤트 복제본 반영 실패 — 재발행 시작: publicationId={}, event={}",
                    publication.getIdentifier(), event.getClass().getSimpleName());
            errorReportPort.reportError(new IllegalStateException(
                    "사용자 이벤트(" + event.getClass().getSimpleName() + ") 복제본 반영 실패 — 10분마다 재발행합니다. publicationId="
                            + publication.getIdentifier()));
        }
        return true;
    }
}
