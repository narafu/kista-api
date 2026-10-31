package com.kista.api.finance.application.service;

import com.kista.api.finance.application.port.output.AssetSnapshotPort;
import com.kista.api.finance.application.port.output.FinanceAccountPort;
import com.kista.api.finance.application.port.output.FinanceBudgetPort;
import com.kista.api.finance.application.port.output.FinanceCategoryPort;
import com.kista.api.finance.application.port.output.FinanceGroupPort;
import com.kista.api.finance.application.port.output.FinanceTransactionPort;
import com.kista.api.finance.application.usecase.FinanceUserDataEraseUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// 사용자 탈퇴 cascade — finance 소유 재무 기록(userId 축) + 그룹 멤버십 정리.
// 그룹 승계는 read-write-read-write라 6개 포트 호출이 한 트랜잭션 안에서 원자적으로 실행돼야 한다 —
// 경계는 FinanceUserCascadeListener(REQUIRES_NEW)가 열고 여기선 합류한다.
@Service
@RequiredArgsConstructor
class FinanceUserDataEraseService implements FinanceUserDataEraseUseCase {

    private final FinanceTransactionPort financeTransactionPort;
    private final AssetSnapshotPort assetSnapshotPort;
    private final FinanceAccountPort financeAccountPort;
    private final FinanceCategoryPort financeCategoryPort;
    private final FinanceBudgetPort financeBudgetPort;
    private final FinanceGroupPort financeGroupPort;

    @Override
    @Transactional
    public void eraseUserData(UUID userId) {
        // 재무 기록 — userId(입력자) 단위로만 정리한다. 그룹을 통째로 지우면 배우자 등 다른
        // 그룹원의 데이터까지 함께 삭제되므로, 소유 축인 group_id가 아니라 입력자 축으로 스코프한다.
        financeTransactionPort.softDeleteByUserId(userId);
        assetSnapshotPort.softDeleteByUserId(userId);
        financeAccountPort.softDeleteByUserId(userId);
        financeCategoryPort.softDeleteByUserId(userId);
        financeBudgetPort.deleteByUserId(userId);

        // 그룹 멤버십 정리 — 이 사용자가 속한 모든 그룹(개인 그룹 포함)에서 멤버십을 소프트 삭제하고,
        // 그 결과 활성 멤버가 0명이 된 그룹은 그룹 자체도 소프트 삭제, OWNER였으면 승계까지 처리한다
        // (FinanceGroupService.leaveGroup과 동일 규칙 — GroupMembershipSupport로 공유).
        for (var group : financeGroupPort.findByMemberUserId(userId)) {
            GroupMembershipSupport.removeMemberAndHandleSuccession(financeGroupPort, group.id(), userId);
        }
    }
}
