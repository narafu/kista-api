package com.kista.finance.application.usecase;

import com.kista.finance.domain.model.AssetSnapshotCommand;
import com.kista.finance.domain.model.BulkFinanceRegisterResult;
import com.kista.finance.domain.model.FinanceTransactionCommand;

import java.util.List;
import java.util.UUID;

public interface BulkFinanceRegisterUseCase {
    // shareToGroup=true면 각 항목을 등록자의 현재 그룹 소유로 원자적 생성한다.
    // 무그룹 유저가 true를 넘기면 IllegalStateException(400).
    BulkFinanceRegisterResult register(UUID userId, boolean shareToGroup,
                                        List<AssetSnapshotCommand> assets,
                                        List<FinanceTransactionCommand> transactions);
}
