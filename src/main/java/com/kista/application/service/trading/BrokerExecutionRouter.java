package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.KisExecutionPort;
import com.kista.domain.port.out.TosExecutionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

// 브로커 무관하게 체결 내역 조회 — KIS/TOSS 분기 캡슐화
@Component
@RequiredArgsConstructor
public class BrokerExecutionRouter {

    private final KisExecutionPort kisExecutionPort;
    private final TosExecutionPort tosExecutionPort;

    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        return switch (account.broker()) {
            case KIS  -> kisExecutionPort.getExecutions(from, to, ticker, account);
            case TOSS -> tosExecutionPort.getExecutions(from, to, ticker, account);
        };
    }
}
