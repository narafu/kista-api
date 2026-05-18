package com.kista.application.service;

import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.in.AdminListTradesUseCase;
import com.kista.domain.port.out.TradeHistoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTradeService implements AdminListTradesUseCase {

    private final TradeHistoryPort tradeHistoryPort; // 거래 내역 조회 포트

    @Override
    public List<TradeHistory> listAll() {
        // 최근 30일 전체 계좌 거래 내역 조회 (symbol 필터 없음)
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        return tradeHistoryPort.findAll(from, to);
    }
}
