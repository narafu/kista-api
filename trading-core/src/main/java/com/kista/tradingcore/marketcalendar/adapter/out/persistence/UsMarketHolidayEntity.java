package com.kista.tradingcore.marketcalendar.adapter.out.persistence;

import com.kista.shared.platform.persistence.BaseCreatedAtEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "us_market_holidays", schema = "trading_ref")
@Getter
@NoArgsConstructor
public class UsMarketHolidayEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
    private UUID id;

    @Column(name = "trade_date", nullable = false, unique = true)
    private LocalDate tradeDate;

    static UsMarketHolidayEntity of(LocalDate tradeDate) {
        UsMarketHolidayEntity entity = new UsMarketHolidayEntity();
        entity.tradeDate = tradeDate;
        return entity;
    }
}
