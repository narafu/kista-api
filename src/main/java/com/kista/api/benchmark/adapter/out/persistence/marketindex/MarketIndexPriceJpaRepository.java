package com.kista.api.benchmark.adapter.out.persistence.marketindex;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MarketIndexPriceJpaRepository extends JpaRepository<MarketIndexPriceEntity, UUID> {
    List<MarketIndexPriceEntity> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(
            String symbol, LocalDate from, LocalDate to);

    Optional<MarketIndexPriceEntity> findTop1BySymbolOrderByTradeDateDesc(String symbol);

    // 분할 조정 — numeric 곱·나눗셈 결과는 close_price(numeric(12,2)) 대입 시 반올림된다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update MarketIndexPriceEntity e set e.closePrice = e.closePrice * :numerator / :denominator
            where e.symbol = :symbol and e.tradeDate <= :through""")
    int scaleCloses(@Param("symbol") String symbol, @Param("through") LocalDate through,
                    @Param("numerator") BigDecimal numerator, @Param("denominator") BigDecimal denominator);
}
