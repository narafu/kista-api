package com.kista.application.service.portfolio;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.PortfolioUseCase;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.OrderPort;
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
    public List<CyclePositionHistoryEntry> getSnapshots(UUID userId, LocalDate from, LocalDate to) {
        return cycleHistoryPort.findBetweenByUser(userId, from, to);
    }

    @Override
    public List<Order> getHistory(UUID userId, LocalDate from, LocalDate to, Ticker ticker) {
        return orderPort.findByUser(userId, from, to, ticker);
    }
}
