package com.kista.adapter.in.schedule;

import com.kista.domain.port.in.TokenUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupScheduler {

    private final TokenUseCase tokenUseCase;

    // 매일 03:00 KST — 만료된 RT 일괄 삭제
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void cleanupExpiredTokens() {
        int deleted = tokenUseCase.cleanupExpiredTokens();
        log.info("만료 refresh_token {} 건 정리 완료", deleted);
    }
}
