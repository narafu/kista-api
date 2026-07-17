package com.kista.adapter.out.persistence.marketindex;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MarketIndexPriceJpaRepository extends JpaRepository<MarketIndexPriceEntity, UUID> {
    List<MarketIndexPriceEntity> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(
            String symbol, LocalDate from, LocalDate to);

    Optional<MarketIndexPriceEntity> findTop1BySymbolOrderByTradeDateDesc(String symbol);
}
