package com.kista.application.service;

import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.out.PortfolioSnapshotPort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class PortfolioService implements GetPortfolioUseCase {

    private final PortfolioSnapshotPort portfolioSnapshotPort;

    public PortfolioService(PortfolioSnapshotPort portfolioSnapshotPort) {
        this.portfolioSnapshotPort = portfolioSnapshotPort;
    }

    @Override
    public PortfolioSnapshot getCurrent() {
        return portfolioSnapshotPort.findRecent(7).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("포트폴리오 스냅샷이 없습니다."));
    }

    @Override
    public List<PortfolioSnapshot> getSnapshots(int days) {
        return portfolioSnapshotPort.findRecent(days);
    }
}
