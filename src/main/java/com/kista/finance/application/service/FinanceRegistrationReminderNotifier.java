package com.kista.finance.application.service;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.finance.domain.port.in.FinanceRegistrationReminderUseCase;
import com.kista.finance.domain.port.out.AssetSnapshotPort;
import com.kista.finance.domain.port.out.FinanceGroupPort;
import com.kista.finance.domain.port.out.FinanceTransactionPort;
import com.kista.notify.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserPort;
import com.kista.domain.port.out.UserSettingsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

// 이번 달 가계부(자산/거래) 등록이 전혀 없는 ACTIVE 사용자에게 알림 — MarketEventNotifier와 동일한
// 배치 조회 + virtual thread 팬아웃 패턴. 그룹 소속 유저는 findMyScope가 이미 groupId 스코프로 조회하므로
// 그룹 내 누구든 등록했으면 자동으로 스킵된다(별도 그룹 스코프 분기 불필요).
@Component
@RequiredArgsConstructor
@Slf4j
class FinanceRegistrationReminderNotifier implements FinanceRegistrationReminderUseCase {

    private static final int MAX_CONCURRENT_SENDS = 10;

    private final UserPort userPort;
    private final FinanceGroupPort financeGroupPort;
    private final UserSettingsPort userSettingsPort;
    private final UserNotificationPort userNotificationPort;
    private final AssetSnapshotPort assetSnapshotPort;
    private final FinanceTransactionPort financeTransactionPort;

    @Override
    public void notifyUsersWithoutThisMonthRegistration(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        List<User> users = userPort.findAllByStatus(User.UserStatus.ACTIVE);
        List<UUID> userIds = users.stream().map(User::id).toList();
        Map<UUID, UserSettings> settingsMap = userSettingsPort.findOrDefaultByUserIds(userIds);
        String monthLabel = month.getMonthValue() + "월";

        Semaphore limiter = new Semaphore(MAX_CONCURRENT_SENDS);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            users.forEach(user -> {
                UserSettings settings = settingsMap.get(user.id());
                if (!settings.isNotificationEnabled(NotificationType.FINANCE_REMINDER)) return;
                executor.submit(() -> checkAndSendWithLimit(limiter, user, monthLabel, from, to));
            });
        }
    }

    // 스코프 조회(2회 DB 왕복)와 발송을 함께 virtual thread로 팬아웃 — 조회만 호출 스레드에서 순차 실행하면
    // 세마포어가 발송만 병렬화하고 정작 느린 조회는 직렬로 남는다
    private void checkAndSendWithLimit(Semaphore limiter, User user, String monthLabel, LocalDate from, LocalDate to) {
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (hasRegistrationThisMonth(user.id(), from, to)) return;
            userNotificationPort.notifyFinanceRegistrationReminder(user, monthLabel);
        } catch (Exception e) {
            log.warn("[userId={}] 가계부 등록 알림 발송 실패: {}", user.id(), e.getMessage());
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
