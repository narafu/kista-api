package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.KisTokenCachePort;
import com.kista.domain.port.out.KisTokenPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisTokenRefreshScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int REFRESH_AHEAD_MINUTES = 10; // 만료 10분 전 선제 갱신

    private final KisTokenCachePort kisTokenCachePort;
    private final AccountPort accountPort;
    private final KisTokenPort kisTokenPort;

    // 1분마다 만료 임박 토큰 선제 갱신 — 요청 중 토큰 재발급으로 인한 503 방지
    @Scheduled(fixedDelay = 60_000)
    public void refreshExpiringTokens() {
        OffsetDateTime threshold = OffsetDateTime.now(KST).plusMinutes(REFRESH_AHEAD_MINUTES);
        List<UUID> expiringIds = kisTokenCachePort.findExpiringAccountIds(threshold);

        if (expiringIds.isEmpty()) return;

        log.info("토큰 선제 갱신 시작 — 만료 임박 계좌 {}개", expiringIds.size());
        for (UUID accountId : expiringIds) {
            try {
                Account account = accountPort.findByIdOrThrow(accountId);
                kisTokenPort.getToken(accountId, account.kisAppKey(), account.kisSecretKey());
                log.info("토큰 선제 갱신 완료: accountId={}", accountId);
            } catch (Exception e) {
                // 갱신 실패는 무시 — 다음 실제 요청 시점에 재시도됨
                log.warn("토큰 선제 갱신 실패 accountId={}: {}", accountId, e.getMessage());
            }
        }
    }
}
