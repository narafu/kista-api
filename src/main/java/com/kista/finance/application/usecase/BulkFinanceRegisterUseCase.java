package com.kista.finance.application.usecase;

import com.kista.finance.domain.model.AssetSnapshotCommand;
import com.kista.finance.domain.model.BulkFinanceRegisterResult;
import com.kista.finance.domain.model.FinanceTransactionCommand;

import java.util.List;
import java.util.UUID;

public interface BulkFinanceRegisterUseCase {
    // shareToGroup=true면 각 항목을 개인 소유로 생성한 뒤 소유자의 현재 그룹으로 공유 전환한다.
    // 무그룹 유저가 true를 넘기면 IllegalStateException(400).
    BulkFinanceRegisterResult register(UUID userId, boolean shareToGroup,
                                        List<AssetSnapshotCommand> assets,
                                        List<FinanceTransactionCommand> transactions);
}
