package com.kista.application.service;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class PortfolioService implements GetPortfolioUseCase {

    private final TradingCycleHistoryPort cycleHistoryPort;

    @Override
    public AccountCycleHistoryEntry getCurrent() {
        // 전체 이력 중 가장 최근 1건 반환
        return cycleHistoryPort.findRecentGlobal(1).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("포트폴리오 데이터가 없습니다."));
    }

    @Override
    public List<AccountCycleHistoryEntry> getSnapshots(LocalDate from, LocalDate to) {
        return cycleHistoryPort.findBetween(from, to);
    }
}
