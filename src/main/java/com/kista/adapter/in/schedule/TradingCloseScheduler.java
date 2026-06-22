package com.kista.adapter.in.schedule;

import com.kista.common.CycleLookups;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.TradingExecutionUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.StrategyCyclePort;
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
public class TradingCloseScheduler {

    private final TradingExecutionUseCase useCase;
    private final AccountPort accountPort;          // 계좌 조회
    private final StrategyPort strategyPort;        // ACTIVE 전략 목록 조회
    private final StrategyCyclePort strategyCyclePort; // 현재 StrategyCycle 조회
    private final UserPort userPort;                // 계좌 소유자 조회
    private final NotifyPort notifyPort;            // 관리자 오류 알림

    @Scheduled(cron = "0 0 4 * * TUE-SAT", zone = TimeZones.KST_ID) // 화~토 04:00 KST (미국 장 마감 30분 전)
    public void run() {
        notifyPort.notifyInfo("마감 매매 스케쥴러 시작");
        List<Strategy> strategies = strategyPort.findAllActive();
        log.info("마감 매매 스케쥴러 시작 — ACTIVE 전략 {}개", strategies.size());

        // 전략별 현재 StrategyCycle·계좌·사용자 조회 — 조회 실패한 전략은 skip
        List<BatchContext> contexts = new ArrayList<>();
        for (Strategy strategy : strategies) {
            try {
                StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
                Account account = accountPort.findByIdOrThrow(strategy.accountId());
                User user = userPort.findByIdOrThrow(account.userId());
                contexts.add(new BatchContext(strategy, currentCycle, account, user));
            } catch (Exception e) {
                log.error("[strategyId={}] 컨텍스트 조회 오류: {}", strategy.id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }

        // 복수종목 현재가 1회 일괄 조회 후 사이클별 순차 실행
        try {
            useCase.executeBatch(contexts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("마감 매매 스케쥴러 인터럽트: {}", e.getMessage());
        } catch (Exception e) {
            log.error("마감 매매 스케쥴러 오류: {}", e.getMessage(), e);
            notifyPort.notifyError(e);
        }

        log.info("마감 매매 스케쥴러 완료");
        notifyPort.notifyInfo("마감 매매 스케쥴러 완료");
    }
}
