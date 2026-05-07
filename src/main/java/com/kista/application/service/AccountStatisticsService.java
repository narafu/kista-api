package com.kista.application.service;

import com.kista.domain.model.Account;
import com.kista.domain.model.Execution;
import com.kista.domain.model.PeriodProfitResult;
import com.kista.domain.model.PresentBalanceResult;
import com.kista.domain.port.in.GetAccountStatisticsUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.KisExecutionPort;
import com.kista.domain.port.out.KisPortfolioPort;
import com.kista.domain.port.out.KisProfitPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountStatisticsService implements GetAccountStatisticsUseCase {

    private final AccountRepository accountRepository;
    private final KisProfitPort kisProfitPort;
    private final KisExecutionPort kisExecutionPort;
    private final KisPortfolioPort kisPortfolioPort;

    @Override
    public PeriodProfitResult getPeriodProfit(UUID accountId, UUID requesterId,
                                               LocalDate from, LocalDate to) {
        Account account = findAndVerify(accountId, requesterId);
        // KIS 예외는 그대로 전파 → 컨트롤러에서 503 처리
        return kisProfitPort.getPeriodProfit(account, from, to);
    }

    @Override
    public List<Execution> getTrades(UUID accountId, UUID requesterId,
                                      LocalDate from, LocalDate to) {
        Account account = findAndVerify(accountId, requesterId);
        return kisExecutionPort.getExecutions(from, account);
    }

    @Override
    public PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId) {
        Account account = findAndVerify(accountId, requesterId);
        return kisPortfolioPort.getPresentBalance(account);
    }

    // 계좌 조회 + 소유권 검증
    private Account findAndVerify(UUID accountId, UUID requesterId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));
        if (!account.userId().equals(requesterId)) {
            throw new SecurityException("계좌에 대한 접근 권한이 없습니다");
        }
        return account;
    }
}
