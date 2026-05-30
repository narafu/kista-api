package com.kista.application.service;

import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.AdminListTradesUseCase;
import com.kista.domain.port.out.OrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTradeService implements AdminListTradesUseCase {

    private final OrderPort orderPort; // 거래 내역 조회 포트

    @Override
    public List<Order> listAll() {
        // 최근 30일 전체 계좌 거래 내역 조회
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        return orderPort.findAll(from, to);
    }
}
