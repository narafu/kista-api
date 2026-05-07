package com.kista.adapter.in.schedule;

import com.kista.domain.model.Account;
import com.kista.domain.model.User;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.NotifyPort;
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
    private final AccountRepository accountRepository; // ACTIVE 계좌 목록 조회
    private final UserRepository userRepository;       // 계좌 소유자 조회
    private final NotifyPort notifyPort;               // 관리자 오류 알림

    @Scheduled(cron = "0 0 4 * * TUE-SAT", zone = "Asia/Seoul") // 화~토 04:00 KST
    public void run() {
        List<Account> accounts = accountRepository.findAllActive();
        log.info("매매 스케줄 시작 — ACTIVE 계좌 {}개", accounts.size());

        for (Account account : accounts) {
            // 계좌별 독립 실행 — 한 계좌 실패 시 다음 계좌 계속
            try {
                User user = userRepository.findById(account.userId())
                        .orElseThrow(() -> new NoSuchElementException("사용자 없음: " + account.userId()));
                useCase.execute(account, user);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[{}] 매매 스케줄 인터럽트: {}", account.nickname(), e.getMessage());
            } catch (Exception e) {
                log.error("[{}] 매매 스케줄 오류: {}", account.nickname(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }
        log.info("매매 스케줄 완료");
    }
}
