package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.order.PortfolioSnapshot;
import com.kista.domain.port.out.PortfolioSnapshotPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // PortfolioSnapshotJpaRepository가 package-private
public class PortfolioSnapshotPersistenceAdapter implements PortfolioSnapshotPort {

    private final PortfolioSnapshotJpaRepository repository;

    @Override
    public void save(PortfolioSnapshot s) {
        repository.save(toEntity(s));
    }

    @Override
    public List<PortfolioSnapshot> findRecent(int days) {
        return repository.findRecent(LocalDate.now().minusDays(days))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private PortfolioSnapshotEntity toEntity(PortfolioSnapshot s) {
        return new PortfolioSnapshotEntity(
                s.id(), s.snapshotDate(), s.ticker(), s.holdings(),
                s.avgPrice(), s.marketValueUsd(),
                s.usdDeposit(), s.totalAssetUsd(), s.accountId()
        );
    }

    private PortfolioSnapshot toDomain(PortfolioSnapshotEntity e) {
        return new PortfolioSnapshot(
                e.getId(), e.getSnapshotDate(), e.getTicker(), e.getHoldings(),
                e.getAvgPrice(), e.getMarketValueUsd(),
                e.getUsdDeposit(), e.getTotalAssetUsd(), e.getAccountId(), e.getCreatedAt()
        );
    }
}
