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
                                          UUID categoryId, UUID createdBy) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        return transactionPort.findByGroupId(groupId, from, to, categoryId, createdBy);
    }

    @Override
    public FinanceTransaction create(UUID userId, UUID requestedGroupId, FinanceTransactionCommand command) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        verifyCategory(groupId, command.categoryId());
        FinanceTransaction transaction = new FinanceTransaction(null, groupId, command.categoryId(), userId,
                command.transactionDate(), command.amount(), command.memo(), null);
        FinanceTransaction saved = transactionPort.save(transaction);
        log.info("거래내역 등록: groupId={}, transactionId={}", groupId, saved.id());
        return saved;
    }

    @Override
    public FinanceTransaction update(UUID transactionId, UUID userId, FinanceTransactionCommand command) {
        FinanceTransaction existing = transactionPort.findByIdOrThrow(transactionId);
        financeGroupPort.resolveGroupId(userId, existing.groupId());
        verifyCategory(existing.groupId(), command.categoryId());
        FinanceTransaction updated = new FinanceTransaction(existing.id(), existing.groupId(), command.categoryId(),
                existing.createdBy(), command.transactionDate(), command.amount(), command.memo(), existing.createdAt());
        return transactionPort.save(updated);
    }

    // categoryId가 이 그룹에서 실제로 접근 가능한지(시스템이거나 같은 그룹)와, 자산 스냅샷 전용 타입이 아닌지
    // 확인한다 — 없으면 다른 그룹의 비공개 카테고리를 지정해도 그대로 저장돼 그룹 합계가 오염된다.
    private void verifyCategory(UUID groupId, UUID categoryId) {
        FinanceCategory category = financeCategoryPort.findByIdOrThrow(categoryId);
        category.verifyOwnedBy(groupId);
        if (category.type() == FinanceCategory.Type.ASSET) {
            throw new IllegalArgumentException("자산 카테고리는 거래내역에 사용할 수 없습니다");
        }
    }

    @Override
    public void delete(UUID transactionId, UUID userId) {
        FinanceTransaction existing = transactionPort.findByIdOrThrow(transactionId);
        financeGroupPort.resolveGroupId(userId, existing.groupId());
        transactionPort.softDelete(transactionId);
        log.info("거래내역 삭제: transactionId={}, userId={}", transactionId, userId);
    }
}
