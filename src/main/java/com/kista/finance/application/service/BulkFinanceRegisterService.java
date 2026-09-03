package com.kista.finance.application.service;

import com.kista.finance.domain.model.AssetSnapshotCommand;
import com.kista.finance.domain.model.BulkFinanceRegisterResult;
import com.kista.finance.domain.model.FinanceTransactionCommand;
import com.kista.finance.domain.port.in.AssetSnapshotUseCase;
import com.kista.finance.domain.port.in.BulkFinanceRegisterUseCase;
import com.kista.finance.domain.port.in.FinanceTransactionUseCase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 항목별 독립 처리 — AssetSnapshotService/FinanceTransactionService의 create()가 이미 자체 트랜잭션 경계라
// 여기서 전체를 하나의 @Transactional로 묶지 않는다. 한 항목 실패가 나머지 항목 등록을 막지 않기 위함.
@Service
class BulkFinanceRegisterService implements BulkFinanceRegisterUseCase {

    private final AssetSnapshotUseCase assetSnapshotUseCase;
    private final FinanceTransactionUseCase financeTransactionUseCase;

    BulkFinanceRegisterService(AssetSnapshotUseCase assetSnapshotUseCase, FinanceTransactionUseCase financeTransactionUseCase) {
        this.assetSnapshotUseCase = assetSnapshotUseCase;
        this.financeTransactionUseCase = financeTransactionUseCase;
    }

    @Override
    public BulkFinanceRegisterResult register(UUID userId, UUID requestedGroupId,
                                               List<AssetSnapshotCommand> assets,
                                               List<FinanceTransactionCommand> transactions) {
        List<String> failures = new ArrayList<>();
        int assetSuccess = 0;
        int txSuccess = 0;

        for (AssetSnapshotCommand command : assets) {
            try {
                assetSnapshotUseCase.create(userId, requestedGroupId, command);
                assetSuccess++;
            } catch (Exception e) {
                failures.add("자산(" + command.memo() + "): " + e.getMessage());
            }
        }

        for (FinanceTransactionCommand command : transactions) {
            try {
                financeTransactionUseCase.create(userId, requestedGroupId, command);
                txSuccess++;
            } catch (Exception e) {
                failures.add("거래(" + command.memo() + "): " + e.getMessage());
            }
        }

        return new BulkFinanceRegisterResult(assetSuccess, txSuccess, failures);
    }
}
