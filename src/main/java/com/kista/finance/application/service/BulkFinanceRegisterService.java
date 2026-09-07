package com.kista.finance.application.service;

import com.kista.finance.domain.model.AssetSnapshotCommand;
import com.kista.finance.domain.model.BulkFinanceRegisterResult;
import com.kista.finance.domain.model.FinanceTransactionCommand;
import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.application.usecase.AssetSnapshotUseCase;
import com.kista.finance.application.usecase.BulkFinanceRegisterUseCase;
import com.kista.finance.application.usecase.FinanceTransactionUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

// 항목별 독립 처리 — AssetSnapshotService/FinanceTransactionService의 create()/shareToGroup()이 이미
// 자체 트랜잭션 경계라 여기서 전체를 하나의 @Transactional로 묶지 않는다. 한 항목 실패가 나머지를 막지 않기 위함.
@Slf4j
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
        // 무그룹 유저의 공유 요청은 무일 생성 후 전량 롤백 대신 진입에서 차단 (kista-ui가 토글을 숨기므로 방어용)
        if (shareToGroup && financeGroupPort.findCurrentGroupId(userId).isEmpty()) {
            throw new IllegalStateException("소속된 그룹이 없습니다");
        }

        List<String> failures = new ArrayList<>();
        int assetSuccess = 0;
        int txSuccess = 0;

        for (AssetSnapshotCommand command : assets) {
            UUID createdId = null;
            try {
                // 신규 등록은 항상 개인 소유 (requestedGroupId 자리는 죽은 값 → null)
                createdId = assetSnapshotUseCase.create(userId, null, command).id();
                if (shareToGroup) {
                    assetSnapshotUseCase.shareToGroup(createdId, userId);
                }
                assetSuccess++;
            } catch (Exception e) {
                failures.add("자산(" + command.memo() + ")"
                        + rollback(createdId, id -> assetSnapshotUseCase.delete(id, userId), e));
            }
        }

        for (FinanceTransactionCommand command : transactions) {
            UUID createdId = null;
            try {
                createdId = financeTransactionUseCase.create(userId, null, command).id();
                if (shareToGroup) {
                    financeTransactionUseCase.shareToGroup(createdId, userId);
                }
                txSuccess++;
            } catch (Exception e) {
                failures.add("거래(" + command.memo() + ")"
                        + rollback(createdId, id -> financeTransactionUseCase.delete(id, userId), e));
            }
        }

        return new BulkFinanceRegisterResult(assetSuccess, txSuccess, failures);
    }

    // createdId=null이면 create 단계에서 실패 — 정리할 게 없다.
    // createdId!=null이면 share 단계 실패 — 방금 만든 개인 레코드를 삭제해 롤백한다.
    private String rollback(UUID createdId, Consumer<UUID> deleter, Exception cause) {
        if (createdId == null) {
            return ": " + cause.getMessage();
        }
        try {
            deleter.accept(createdId);
            return ": 그룹 공유 실패로 등록 취소 — " + cause.getMessage();
        } catch (Exception cleanupFailure) {
            // 고아 레코드(그룹 미전환 개인 소유)가 남는다 — ops가 id로 추적할 수 있게 남긴다
            log.warn("그룹 공유 실패 후 개인 레코드 정리도 실패: id={}", createdId, cleanupFailure);
            return ": 그룹 공유 실패, 개인 레코드 정리도 실패(수동 확인 필요) — " + cause.getMessage();
        }
    }
}
