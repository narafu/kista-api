package com.kista.finance.adapter.out;

import com.kista.user.application.event.UserDeletedEvent;
import com.kista.finance.application.port.output.AssetSnapshotPort;
import com.kista.finance.application.port.output.FinanceAccountPort;
import com.kista.finance.application.port.output.FinanceBudgetPort;
import com.kista.finance.application.port.output.FinanceCategoryPort;
import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.application.port.output.FinanceTransactionPort;
import com.kista.finance.domain.model.FinanceGroup;
import com.kista.finance.domain.model.FinanceGroupMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Comparator;

// 사용자 탈퇴 cascade — finance 소유 재무 기록(userId 축) + 그룹 멤버십 정리를 독립적으로 처리.
// UserCascadeDeleter가 직접 6개 포트를 호출하던 것을 이벤트 구독으로 전환(user↔finance 순환 해소).
// 그룹 소유권 승계 로직은 원본 UserCascadeDeleter 구현을 그대로 이관.
// AFTER_COMMIT 시점엔 원본 트랜잭션이 종료돼 있으므로 REQUIRES_NEW로 새 트랜잭션을 연다 —
// 그룹 승계는 read-write-read-write라 6개 포트 호출이 한 트랜잭션 안에서 원자적으로 실행돼야 한다.
// 빈 이름 명시 — trading 모듈에도 동명 클래스(com.kista.trading.adapter.out.UserCascadeListener)가 있어
// 컴포넌트 스캔 기본 빈 이름('userCascadeListener')이 충돌한다.
@Component("financeUserCascadeListener")
@RequiredArgsConstructor
public class UserCascadeListener {

    private final FinanceTransactionPort financeTransactionPort;
    private final AssetSnapshotPort assetSnapshotPort;
    private final FinanceAccountPort financeAccountPort;
    private final FinanceCategoryPort financeCategoryPort;
    private final FinanceBudgetPort financeBudgetPort;
    private final FinanceGroupPort financeGroupPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserDeleted(UserDeletedEvent event) {
        var userId = event.userId();

        // 재무 기록 — userId(입력자) 단위로만 정리한다. 그룹을 통째로 지우면 배우자 등 다른
        // 그룹원의 데이터까지 함께 삭제되므로, 소유 축인 group_id가 아니라 입력자 축으로 스코프한다.
        financeTransactionPort.softDeleteByUserId(userId);
        assetSnapshotPort.softDeleteByUserId(userId);
        financeAccountPort.softDeleteByUserId(userId);
        financeCategoryPort.softDeleteByUserId(userId);
        financeBudgetPort.deleteByUserId(userId);

        // 그룹 멤버십 정리 — 이 사용자가 속한 모든 그룹(개인 그룹 포함)에서 멤버십을 소프트 삭제하고,
        // 그 결과 활성 멤버가 0명이 된 그룹은 그룹 자체도 소프트 삭제한다.
        for (var group : financeGroupPort.findByMemberUserId(userId)) {
            boolean wasOwner = financeGroupPort.findRole(group.id(), userId)
                    .filter(role -> role == FinanceGroup.MemberRole.OWNER)
                    .isPresent();
            financeGroupPort.softDeleteMembership(group.id(), userId);
            var remaining = financeGroupPort.findActiveMembers(group.id());
            if (remaining.isEmpty()) {
                financeGroupPort.softDelete(group.id());
            } else if (wasOwner && remaining.stream().noneMatch(m -> m.role() == FinanceGroup.MemberRole.OWNER)) {
                FinanceGroupMember successor = remaining.stream()
                        .min(Comparator.comparing(FinanceGroupMember::joinedAt))
                        .orElseThrow();
                financeGroupPort.updateMemberRole(group.id(), successor.userId(), FinanceGroup.MemberRole.OWNER);
            }
        }
    }
}
