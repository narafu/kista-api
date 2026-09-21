package com.kista.marketcalendar.adapter.out.persistence;

import com.kista.platform.persistence.BaseCreatedAtEntity;
import jakarta.persistence.*;
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
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trade_date", nullable = false, unique = true)
    private LocalDate tradeDate;

    static UsMarketHolidayEntity of(LocalDate tradeDate) {
        UsMarketHolidayEntity entity = new UsMarketHolidayEntity();
        entity.tradeDate = tradeDate;
        return entity;
    }
}
