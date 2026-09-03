package com.kista.application.service.user;

import com.kista.application.event.UserDeletedEvent;
import com.kista.finance.domain.model.FinanceGroup;
import com.kista.finance.domain.model.FinanceGroupMember;
import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;
import com.kista.finance.application.port.output.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;

// UserService.deleteMe / AdminService.deleteUser 공통 cascade 삭제 — 포지션 → 사이클 → 전략 → 계좌 → 재무 → 사용자 순 (FK CASCADE 대체)
@Component
@RequiredArgsConstructor
public class UserCascadeDeleter {

    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final AccountPort accountPort;
    private final FinanceCategoryPort financeCategoryPort;
    private final FinanceAccountPort financeAccountPort;
    private final FinanceBudgetPort financeBudgetPort;
    private final FinanceTransactionPort financeTransactionPort;
    private final AssetSnapshotPort assetSnapshotPort;
    private final FinanceGroupPort financeGroupPort;
    private final UserPort userPort;
    private final RefreshTokenPort refreshTokenPort; // 모든 RT 삭제
    private final BlacklistPort blacklistPort;        // 남은 AT 즉시 차단
    private final ApplicationEventPublisher eventPublisher;

    private static final Duration AT_TTL = Duration.ofMinutes(15); // AT 만료까지 차단 유지

    public void deleteCascade(UUID userId) {
        // CyclePosition → StrategyCycle → Strategy → Account → User 순서로 소프트 삭제
        cyclePositionPort.deleteByUserId(userId);
        strategyCyclePort.deleteByUserId(userId);
        strategyPort.deleteByUserId(userId);
        accountPort.deleteByUserId(userId);

        // 재무 기록 — userId(입력자) 단위로만 정리한다. 그룹을 통째로 지우면 배우자 등 다른
        // 그룹원의 데이터까지 함께 삭제되므로, 소유 축인 group_id가 아니라 입력자 축으로 스코프한다.
        financeTransactionPort.softDeleteByUserId(userId);
        assetSnapshotPort.softDeleteByUserId(userId);
        financeAccountPort.softDeleteByUserId(userId);
        financeCategoryPort.softDeleteByUserId(userId);   // 시스템 카테고리는 userId가 null이라 자동 제외됨
        financeBudgetPort.deleteByUserId(userId);          // 예산은 파생 설정이라 하드 삭제

        // 그룹 멤버십 정리 — 이 사용자가 속한 모든 그룹(개인 그룹 포함)에서 멤버십을 소프트 삭제하고,
        // 그 결과 활성 멤버가 0명이 된 그룹(더 이상 아무도 없는 개인 그룹, 또는 마지막 멤버가 나간 공유 그룹)은
        // 그룹 자체도 소프트 삭제한다. 그룹 이탈(leaveGroup, FinanceGroupService 소관)과 달리 회원 탈퇴는
        // 데이터를 옮기지 않고 지운다 — 두 유스케이스를 같은 코드로 처리하지 않는다.
        for (var group : financeGroupPort.findByMemberUserId(userId)) {
            boolean wasOwner = financeGroupPort.findRole(group.id(), userId)
                    .filter(role -> role == FinanceGroup.MemberRole.OWNER)
                    .isPresent();
            financeGroupPort.softDeleteMembership(group.id(), userId);
            var remaining = financeGroupPort.findActiveMembers(group.id());
            if (remaining.isEmpty()) {
                financeGroupPort.softDelete(group.id());
            } else if (wasOwner && remaining.stream().noneMatch(m -> m.role() == FinanceGroup.MemberRole.OWNER)) {
                // 탈퇴한 사용자가 이 그룹의 유일한 OWNER였고 다른 멤버가 남아 있으면, 그 그룹은 아무도
                // 다시 초대할 수 없는 상태가 된다(invite()는 OWNER만 허용) — FinanceGroupService.leaveGroup과
                // 동일한 규칙으로 가장 먼저 합류한 남은 멤버를 새 OWNER로 승격한다.
                FinanceGroupMember successor = remaining.stream()
                        .min(Comparator.comparing(FinanceGroupMember::joinedAt))
                        .orElseThrow();
                financeGroupPort.updateMemberRole(group.id(), successor.userId(), FinanceGroup.MemberRole.OWNER);
            }
        }

        userPort.delete(userId);
        // 인증 정리 — RT 전체 삭제 후 AT 즉시 차단
        refreshTokenPort.deleteAllByUserId(userId);
        blacklistPort.add(userId, AT_TTL);
        // 삭제 완료 이벤트 발행 (트랜잭션 커밋 후 리스너 실행)
        eventPublisher.publishEvent(new UserDeletedEvent(userId));
    }
}
