package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceAccount;
import com.kista.domain.model.finance.FinanceAccountCommand;
import com.kista.domain.port.in.FinanceAccountUseCase;
import com.kista.domain.port.out.AssetSnapshotPort;
import com.kista.domain.port.out.FinanceAccountPort;
import com.kista.domain.port.out.FinanceGroupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class FinanceAccountService implements FinanceAccountUseCase {

    private final FinanceAccountPort accountPort;
    private final FinanceGroupPort financeGroupPort;
    private final AssetSnapshotPort assetSnapshotPort;

    @Override
    @Transactional(readOnly = true)
    public List<FinanceAccount> list(UUID userId, UUID requestedGroupId) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return accountPort.findMyScope(userId, currentGroupId);
    }

    // 신규 등록은 항상 개인 소유로 저장한다 — requestedGroupId는 무시.
    @Override
    public FinanceAccount create(UUID userId, UUID requestedGroupId, FinanceAccountCommand command) {
        FinanceAccount account = new FinanceAccount(null, null, userId, command.accountType(),
                command.name(), command.accountNo(), command.memo(), null);
        FinanceAccount saved = accountPort.save(account);
        log.info("계좌 등록: userId={}, accountId={}", userId, saved.id());
        return saved;
    }

    @Override
    public FinanceAccount update(UUID accountId, UUID userId, FinanceAccountCommand command) {
        // findByIdOrThrow(삭제된 계좌도 조회됨)를 쓰면 안 됨 — FinanceAccount에 deletedAt이 없어
        // save() merge 시 삭제 상태가 조용히 풀려버린다(코드리뷰에서 발견, 2026-08-19).
        FinanceAccount existing = accountPort.findActiveByIdOrThrow(accountId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        FinanceAccount updated = new FinanceAccount(existing.id(), existing.groupId(), existing.userId(),
                command.accountType(), command.name(), command.accountNo(), command.memo(), existing.createdAt());
        return accountPort.save(updated);
    }

    @Override
    public void delete(UUID accountId, UUID userId) {
        FinanceAccount existing = accountPort.findByIdOrThrow(accountId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        if (assetSnapshotPort.existsByAccountId(accountId)) {
            throw new FinanceAccount.LinkedAssetSnapshotsException();
        }
        accountPort.softDelete(accountId);
        log.info("계좌 삭제: accountId={}, userId={}", accountId, userId);
    }

    // 개인 소유 계좌를 소유자가 자신의 현재 그룹으로 전환한다. 본인 것만 전환 가능(그룹 멤버 전체 아님).
    @Override
    public FinanceAccount shareToGroup(UUID accountId, UUID userId) {
        FinanceAccount existing = accountPort.findActiveByIdOrThrow(accountId);
        return GroupShareSupport.shareToGroup(existing, userId, financeGroupPort.findCurrentGroupId(userId),
                        "본인 소유 계좌만 그룹에 공유할 수 있습니다")
                .map(shared -> {
                    FinanceAccount saved = accountPort.save(shared);
                    log.info("계좌 그룹 공유 전환: accountId={}, groupId={}", accountId, saved.groupId());
                    return saved;
                })
                .orElse(existing);
    }

    // 그룹 공유 계좌를 개인 소유로 되돌린다. 소유자는 그대로 유지, groupId만 null로.
    @Override
    public FinanceAccount unshare(UUID accountId, UUID userId) {
        FinanceAccount existing = accountPort.findActiveByIdOrThrow(accountId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return GroupShareSupport.unshare(existing, userId, currentGroupId)
                .map(personal -> {
                    FinanceAccount saved = accountPort.save(personal);
                    log.info("계좌 그룹 공유 해제: accountId={}, userId={}", accountId, userId);
                    return saved;
                })
                .orElse(existing);
    }
}
