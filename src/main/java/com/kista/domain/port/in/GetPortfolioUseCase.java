package com.kista.domain.port.in;

import com.kista.domain.model.PortfolioSnapshot;

import java.util.List;

public interface GetPortfolioUseCase {
    PortfolioSnapshot getCurrent();
    List<PortfolioSnapshot> getSnapshots(int days);
}
