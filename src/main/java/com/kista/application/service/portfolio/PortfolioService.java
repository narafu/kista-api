package com.kista.application.service.portfolio;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.in.PortfolioUseCase;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.TradingCyclePositionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
class PortfolioService implements PortfolioUseCase {

    private final TradingCyclePositionPort cycleHistoryPort;
    private final OrderPort orderPort; // 거래 이력 조회

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

    @Override
    public List<Order> getHistory(LocalDate from, LocalDate to, Ticker ticker) {
        return orderPort.findBy(from, to, ticker);
    }
}
