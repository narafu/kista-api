package com.kista.domain.port.in;

import com.kista.domain.model.finance.AssetSnapshotCommand;
import com.kista.domain.model.finance.BulkFinanceRegisterResult;
import com.kista.domain.model.finance.FinanceTransactionCommand;

import java.util.List;
import java.util.UUID;

public interface BulkFinanceRegisterUseCase {
    BulkFinanceRegisterResult register(UUID userId, UUID requestedGroupId,
                                        List<AssetSnapshotCommand> assets,
                                        List<FinanceTransactionCommand> transactions);
}
