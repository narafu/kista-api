package com.kista.adapter.in.schedule;

import com.kista.common.TimeZones;
import com.kista.domain.port.in.FetchFearGreedUseCase;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

// 매일 09:10 KST — 크립토·CNN 공포탐욕지수 수집 및 저장
@Slf4j
@Component
@RequiredArgsConstructor
public class FearGreedScheduler {

    private final FetchFearGreedUseCase fetchFearGreedUseCase;
    private final NotifyPort notifyPort; // 스케쥴러 시작/종료 알림

    @Scheduled(cron = "0 10 9 * * *", zone = TimeZones.KST_ID) // 매일 09:10 KST
    public void run() {
        notifyPort.notifyInfo("공포탐욕지수 수집 스케쥴러 시작");
        LocalDate today = LocalDate.now(TimeZones.KST);
        log.info("공포탐욕지수 수집 스케쥴러 시작 (date={})", today);
        try {
            fetchFearGreedUseCase.fetchAndSave(today);
        } catch (Exception e) {
            log.error("공포탐욕지수 수집 실패: {}", e.getMessage(), e);
        }
        log.info("공포탐욕지수 수집 스케쥴러 완료");
        notifyPort.notifyInfo("공포탐욕지수 수집 스케쥴러 완료");
    }
}
