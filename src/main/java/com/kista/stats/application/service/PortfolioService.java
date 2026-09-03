package com.kista.stats.application.service;

import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.stats.application.usecase.PortfolioUseCase;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.OrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class PortfolioService implements PortfolioUseCase {

    private final CyclePositionPort cycleHistoryPort;
    private final OrderPort orderPort; // 거래 이력 조회

    @Override
    public CyclePositionHistoryEntry getCurrent(UUID userId) {
        // 요청 사용자의 가장 최근 포지션 1건 반환
        return cycleHistoryPort.findRecentByUser(userId, 1).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("포트폴리오 데이터가 없습니다."));
    }

    @Override
    public List<Order> getHistory(UUID userId, LocalDate from, LocalDate to, Ticker ticker) {
        return orderPort.findByUser(userId, from, to, ticker);
    }
}
