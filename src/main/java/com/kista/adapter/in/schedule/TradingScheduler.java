package com.kista.adapter.in.schedule;

import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.NotifyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TradingScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradingScheduler.class);

    private final ExecuteTradingUseCase useCase;
    private final NotifyPort notifyPort; // 오류 발생 시 텔레그램 알림

    public TradingScheduler(ExecuteTradingUseCase useCase, NotifyPort notifyPort) {
        this.useCase = useCase;
        this.notifyPort = notifyPort;
    }

    @Scheduled(cron = "0 0 4 * * MON-FRI", zone = "Asia/Seoul")
    public void run() {
        log.info("매매 스케줄 시작");
        try {
            useCase.execute();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("매매 스케줄 인터럽트: {}", e.getMessage());
        } catch (Exception e) {
            log.error("매매 스케줄 오류: {}", e.getMessage(), e);
            notifyPort.notifyError(e);
        }
    }
}
