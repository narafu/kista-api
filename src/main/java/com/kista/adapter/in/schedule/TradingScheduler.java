package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.TradingExecutionUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.TradingCyclePort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradingScheduler {

    private final TradingExecutionUseCase useCase;
    private final AccountPort accountPort;          // 계좌 조회
    private final TradingCyclePort cyclePort;       // ACTIVE 사이클 목록 조회
    private final UserPort userPort;                // 계좌 소유자 조회
    private final NotifyPort notifyPort;                        // 관리자 오류 알림

    @Scheduled(cron = "0 0 4 * * TUE-SAT", zone = "Asia/Seoul") // 화~토 04:00 KST
    public void run() {
        List<TradingCycle> cycles = cyclePort.findAllActive();
        log.info("매매 스케줄 시작 — ACTIVE 사이클 {}개", cycles.size());

        // 사이클별 계좌·사용자 조회 — 조회 실패한 사이클은 skip
        List<BatchContext> contexts = new ArrayList<>();
        for (TradingCycle cycle : cycles) {
            try {
                Account account = accountPort.findByIdOrThrow(cycle.accountId());
                User user = userPort.findByIdOrThrow(account.userId());
                contexts.add(new BatchContext(cycle, account, user));
            } catch (Exception e) {
                log.error("[cycleId={}] 컨텍스트 조회 오류: {}", cycle.id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }

        // 복수종목 현재가 1회 일괄 조회 후 사이클별 순차 실행
        try {
            useCase.executeBatch(contexts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("매매 스케줄 인터럽트: {}", e.getMessage());
        } catch (Exception e) {
            log.error("매매 스케줄 오류: {}", e.getMessage(), e);
            notifyPort.notifyError(e);
        }

        log.info("매매 스케줄 완료");
    }
}
