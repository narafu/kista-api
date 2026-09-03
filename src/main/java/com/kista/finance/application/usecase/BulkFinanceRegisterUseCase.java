package com.kista.finance.application.usecase;

import com.kista.finance.domain.model.AssetSnapshotCommand;
import com.kista.finance.domain.model.BulkFinanceRegisterResult;
import com.kista.finance.domain.model.FinanceTransactionCommand;

import java.util.List;
import java.util.UUID;

public interface BulkFinanceRegisterUseCase {
    BulkFinanceRegisterResult register(UUID userId, UUID requestedGroupId,
                                        List<AssetSnapshotCommand> assets,
                                        List<FinanceTransactionCommand> transactions);
}
