package com.kista.finance.application.service;

import com.kista.finance.domain.model.AssetSnapshotCommand;
import com.kista.finance.domain.model.BulkFinanceRegisterResult;
import com.kista.finance.domain.model.FinanceTransactionCommand;
import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.application.usecase.AssetSnapshotUseCase;
import com.kista.finance.application.usecase.BulkFinanceRegisterUseCase;
import com.kista.finance.application.usecase.FinanceTransactionUseCase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

// 항목별 독립 처리 — AssetSnapshotService/FinanceTransactionService의 create()가 이미 자체 트랜잭션 경계라
// 여기서 전체를 하나의 @Transactional로 묶지 않는다. 한 항목 실패가 나머지 항목 등록을 막지 않기 위함.
// shareToGroup=true면 각 create가 원자적으로 그룹 소유로 생성한다(별도 share 전환 단계 없음).
@Service
class BulkFinanceRegisterService implements BulkFinanceRegisterUseCase {

    private final AssetSnapshotUseCase assetSnapshotUseCase;
    private final FinanceTransactionUseCase financeTransactionUseCase;
    private final FinanceGroupPort financeGroupPort;

    BulkFinanceRegisterService(AssetSnapshotUseCase assetSnapshotUseCase,
                               FinanceTransactionUseCase financeTransactionUseCase,
                               FinanceGroupPort financeGroupPort) {
        this.assetSnapshotUseCase = assetSnapshotUseCase;
        this.financeTransactionUseCase = financeTransactionUseCase;
        this.financeGroupPort = financeGroupPort;
    }

    @Override
    public BulkFinanceRegisterResult register(UUID userId, boolean shareToGroup,
                                               List<AssetSnapshotCommand> assets,
                                               List<FinanceTransactionCommand> transactions) {
        // 무그룹 유저의 공유 요청은 항목마다 실패시키지 않고 진입에서 차단 (kista-ui가 토글을 숨기므로 방어용)
        if (shareToGroup && financeGroupPort.findCurrentGroupId(userId).isEmpty()) {
            throw new IllegalStateException("소속된 그룹이 없습니다");
        }

        List<String> failures = new ArrayList<>();
        int assetSuccess = processAll(assets, c -> assetSnapshotUseCase.create(userId, shareToGroup, c),
                AssetSnapshotCommand::memo, "자산", failures);
        int txSuccess = processAll(transactions, c -> financeTransactionUseCase.create(userId, shareToGroup, c),
                FinanceTransactionCommand::memo, "거래", failures);

        return new BulkFinanceRegisterResult(assetSuccess, txSuccess, failures);
    }

    // 항목별 독립 처리 — 하나 실패해도 나머지는 계속 진행하고 실패 사유만 수집
    private <T> int processAll(List<T> items, Consumer<T> create, Function<T, String> labelOf,
                                String kind, List<String> failures) {
        int success = 0;
        for (T item : items) {
            try {
                create.accept(item);
                success++;
            } catch (Exception e) {
                failures.add(kind + "(" + labelOf.apply(item) + "): " + e.getMessage());
            }
        }
        return success;
    }
}
