package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;

import java.util.List;

public interface GetPortfolioUseCase {
    AccountCycleHistoryEntry getCurrent();
    List<AccountCycleHistoryEntry> getSnapshots(int days);
}
