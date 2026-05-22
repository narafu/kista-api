package com.kista.adapter.in.schedule;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.StrategyRepository;
import com.kista.domain.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.NoSuchElementException;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradingScheduler {

    private final ExecuteTradingUseCase useCase;
    private final AccountRepository accountRepository; // 계좌 조회
    private final StrategyRepository strategyRepository; // ACTIVE 전략 목록 조회
    private final UserRepository userRepository;         // 계좌 소유자 조회
    private final NotifyPort notifyPort;                 // 관리자 오류 알림

    @Scheduled(cron = "0 0 4 * * TUE-SAT", zone = "Asia/Seoul") // 화~토 04:00 KST
    public void run() {
        List<Strategy> strategies = strategyRepository.findAllActive();
        log.info("매매 스케줄 시작 — ACTIVE 전략 {}개", strategies.size());

        for (Strategy strategy : strategies) {
            // 전략별 독립 실행 — 한 전략 실패 시 다음 전략 계속
            try {
                Account account = accountRepository.findByIdOrThrow(strategy.accountId());
                User user = userRepository.findById(account.userId())
                        .orElseThrow(() -> new NoSuchElementException("사용자 없음: " + account.userId()));
                useCase.execute(strategy, account, user);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[strategyId={}] 매매 스케줄 인터럽트: {}", strategy.id(), e.getMessage());
            } catch (Exception e) {
                log.error("[strategyId={}] 매매 스케줄 오류: {}", strategy.id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }
        log.info("매매 스케줄 완료");
    }
}
