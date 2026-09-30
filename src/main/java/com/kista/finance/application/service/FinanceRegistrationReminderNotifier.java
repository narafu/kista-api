package com.kista.finance.application.service;

import com.kista.finance.application.port.output.AssetSnapshotPort;
import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.application.port.output.FinanceTransactionPort;
import com.kista.finance.application.usecase.FinanceRegistrationReminderUseCase;
import com.kista.sharedkernel.NotificationType;
import com.kista.sharedkernel.UserNotificationRequestedEvent;
import com.kista.sharedkernel.UserStatus;
import com.kista.user.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

// 이번 달 가계부(자산/거래) 등록이 전혀 없는 ACTIVE 사용자에게 알림을 요청한다 — MarketEventNotifier와 동일한
// 배치 조회 + virtual thread 팬아웃 패턴. 이 모듈은 등록 여부만 판단하고 발송(채널 라우팅·사용자 알림 설정 게이트)은
// UserNotificationRequestedEvent 구독자(notify)가 맡는다. 그룹 소속 유저는 findMyScope가 이미 groupId 스코프로
// 조회하므로 그룹 내 누구든 등록했으면 자동으로 스킵된다(별도 그룹 스코프 분기 불필요).
@Component
@RequiredArgsConstructor
@Slf4j
class FinanceRegistrationReminderNotifier implements FinanceRegistrationReminderUseCase {

    private static final int MAX_CONCURRENT_CHECKS = 10;
    private static final String REMINDER_TITLE = "가계부 등록을 아직 안 하셨어요";

    private final UserPort userPort;                            // ACTIVE 사용자 id 목록만 조회 (identity 조회)
    private final FinanceGroupPort financeGroupPort;
    private final AssetSnapshotPort assetSnapshotPort;
    private final FinanceTransactionPort financeTransactionPort;
    private final ApplicationEventPublisher eventPublisher;     // 사용자 알림 요청 이벤트 발행

    @Override
    public void notifyUsersWithoutThisMonthRegistration(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        List<UUID> userIds = userPort.findIdsByStatus(UserStatus.ACTIVE);
        String body = month.getMonthValue() + "월 가계부(자산·수입·소비·저축) 등록이 아직 없어요. 지금 등록해보세요.";

        Semaphore limiter = new Semaphore(MAX_CONCURRENT_CHECKS);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            userIds.forEach(userId -> executor.submit(() -> checkAndRequestWithLimit(limiter, userId, body, from, to)));
        }
    }

    // 스코프 조회(2회 DB 왕복)를 virtual thread로 팬아웃 — 조회를 호출 스레드에서 순차 실행하면 직렬로 남는다.
    // 트랜잭션 밖 스케쥴러 경로라 구독자는 동기 @EventListener로 받는다
    private void checkAndRequestWithLimit(Semaphore limiter, UUID userId, String body, LocalDate from, LocalDate to) {
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (hasRegistrationThisMonth(userId, from, to)) return;
            eventPublisher.publishEvent(new UserNotificationRequestedEvent(
                    userId, NotificationType.FINANCE_REMINDER, REMINDER_TITLE, body));
        } catch (Exception e) {
            log.warn("[userId={}] 가계부 등록 알림 요청 실패: {}", userId, e.getMessage());
        } finally {
            limiter.release();
        }
    }

    private boolean hasRegistrationThisMonth(UUID userId, LocalDate from, LocalDate to) {
        UUID groupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        boolean hasAsset = !assetSnapshotPort.findMyScope(userId, groupId, from, to, null).isEmpty();
        boolean hasTransaction = !financeTransactionPort.findMyScope(userId, groupId, from, to, null, null).isEmpty();
        return hasAsset || hasTransaction;
    }

}
