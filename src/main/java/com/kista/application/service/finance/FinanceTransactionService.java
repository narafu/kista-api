package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.model.finance.FinanceTransaction;
import com.kista.domain.model.finance.FinanceTransactionCommand;
import com.kista.domain.port.in.FinanceTransactionUseCase;
import com.kista.domain.port.out.FinanceCategoryPort;
import com.kista.domain.port.out.FinanceGroupPort;
import com.kista.domain.port.out.FinanceTransactionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class FinanceTransactionService implements FinanceTransactionUseCase {

    private final FinanceTransactionPort transactionPort;
    private final FinanceGroupPort financeGroupPort;
    private final FinanceCategoryPort financeCategoryPort;

    @Override
    @Transactional(readOnly = true)
    public List<FinanceTransaction> list(UUID userId, UUID requestedGroupId, LocalDate from, LocalDate to,
                                          UUID categoryId, UUID filterUserId) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return transactionPort.findMyScope(userId, currentGroupId, from, to, categoryId, filterUserId);
    }

    // 신규 등록은 항상 개인 소유로 저장한다 — requestedGroupId는 무시(그룹 공유는 shareToGroup으로 별도 전환).
    @Override
    public FinanceTransaction create(UUID userId, UUID requestedGroupId, FinanceTransactionCommand command) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        verifyCategory(userId, currentGroupId, command.categoryId());
        FinanceTransaction transaction = new FinanceTransaction(null, null, command.categoryId(), userId,
                command.transactionDate(), command.amount(), command.memo(), null);
        FinanceTransaction saved = transactionPort.save(transaction);
        log.info("거래내역 등록: userId={}, transactionId={}", userId, saved.id());
        return saved;
    }

    @Override
    public FinanceTransaction update(UUID transactionId, UUID userId, FinanceTransactionCommand command) {
        FinanceTransaction existing = transactionPort.findByIdOrThrow(transactionId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        verifyCategory(userId, currentGroupId, command.categoryId());
        FinanceTransaction updated = new FinanceTransaction(existing.id(), existing.groupId(), command.categoryId(),
                existing.userId(), command.transactionDate(), command.amount(), command.memo(), existing.createdAt());
        return transactionPort.save(updated);
    }

    // categoryId가 실제로 접근 가능한지(시스템이거나 본인/내 그룹 소유)와, 자산 스냅샷 전용 타입이 아닌지 확인한다.
    private void verifyCategory(UUID userId, UUID currentGroupId, UUID categoryId) {
        FinanceCategory category = financeCategoryPort.findByIdOrThrow(categoryId);
        category.verifyAccessibleBy(userId, currentGroupId);
        if (category.type() == FinanceCategory.Type.ASSET) {
            throw new IllegalArgumentException("자산 카테고리는 거래내역에 사용할 수 없습니다");
        }
    }

    @Override
    public void delete(UUID transactionId, UUID userId) {
        FinanceTransaction existing = transactionPort.findByIdOrThrow(transactionId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        transactionPort.softDelete(transactionId);
        log.info("거래내역 삭제: transactionId={}, userId={}", transactionId, userId);
    }

    // 개인 소유 거래를 소유자가 자신의 현재 그룹으로 전환한다. 본인 것만 전환 가능(그룹 멤버 전체 아님).
    @Override
    public FinanceTransaction shareToGroup(UUID transactionId, UUID userId) {
        FinanceTransaction existing = transactionPort.findByIdOrThrow(transactionId);
        return GroupShareSupport.shareToGroup(existing, userId, financeGroupPort.findCurrentGroupId(userId),
                        "본인 소유 거래내역만 그룹에 공유할 수 있습니다")
                .map(shared -> {
                    FinanceTransaction saved = transactionPort.save(shared);
                    log.info("거래내역 그룹 공유 전환: transactionId={}, groupId={}", transactionId, saved.groupId());
                    return saved;
                })
                .orElse(existing);
    }

    // 그룹 공유 거래내역을 개인 소유로 되돌린다. 소유자는 그대로 유지, groupId만 null로.
    @Override
    public FinanceTransaction unshare(UUID transactionId, UUID userId) {
        FinanceTransaction existing = transactionPort.findByIdOrThrow(transactionId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return GroupShareSupport.unshare(existing, userId, currentGroupId)
                .map(personal -> {
                    FinanceTransaction saved = transactionPort.save(personal);
                    log.info("거래내역 그룹 공유 해제: transactionId={}, userId={}", transactionId, userId);
                    return saved;
                })
                .orElse(existing);
    }
}
