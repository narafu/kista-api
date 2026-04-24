package com.kista.domain.port.out;

import com.kista.domain.model.PortfolioSnapshot;

import java.util.List;

public interface PortfolioSnapshotPort {
    void save(PortfolioSnapshot s);
    List<PortfolioSnapshot> findRecent(int days);
}
