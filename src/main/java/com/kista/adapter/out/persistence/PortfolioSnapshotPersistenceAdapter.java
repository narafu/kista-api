package com.kista.adapter.out.persistence;

import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.port.out.PortfolioSnapshotPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class PortfolioSnapshotPersistenceAdapter implements PortfolioSnapshotPort {

    private final PortfolioSnapshotJpaRepository repository;

    public PortfolioSnapshotPersistenceAdapter(PortfolioSnapshotJpaRepository repository) {
        this.repository = repository;
    }

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
                s.id(), s.snapshotDate(), s.symbol(), s.qty(),
                s.avgPrice(), s.currentPrice(), s.marketValueUsd(),
                s.usdDeposit(), s.totalAssetUsd()
        );
    }

    private PortfolioSnapshot toDomain(PortfolioSnapshotEntity e) {
        return new PortfolioSnapshot(
                e.getId(), e.getSnapshotDate(), e.getSymbol(), e.getQty(),
                e.getAvgPrice(), e.getCurrentPrice(), e.getMarketValueUsd(),
                e.getUsdDeposit(), e.getTotalAssetUsd(), e.getCreatedAt()
        );
    }
}
