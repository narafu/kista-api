package com.kista.tradingcore.marketcalendar.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "us_market_sessions", schema = "trading_ref")
@Getter
@NoArgsConstructor
class UsMarketSessionEntity {
    @Id
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate; // 미국 거래일

    @Column(name = "open_at")
    private Instant openAt; // 개장 시각, 휴장일은 null

    @Column(name = "close_at")
    private Instant closeAt; // 마감 시각, 휴장일은 null

    static UsMarketSessionEntity of(LocalDate date, Instant openAt, Instant closeAt) {
        UsMarketSessionEntity entity = new UsMarketSessionEntity();
        entity.tradeDate = date;
        entity.openAt = openAt;
        entity.closeAt = closeAt;
        return entity;
    }
}