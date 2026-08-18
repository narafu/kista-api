package com.kista.domain.port.out;

import com.kista.domain.model.finance.FinanceTransaction;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface FinanceTransactionPort {
    // from/to/categoryId/createdBy는 선택적 필터 — null이면 무시
    List<FinanceTransaction> findByGroupId(UUID groupId, LocalDate from, LocalDate to, UUID categoryId, UUID createdBy);

    Optional<FinanceTransaction> findById(UUID id);

    default FinanceTransaction findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("거래내역을 찾을 수 없습니다: " + id));
    }

    FinanceTransaction save(FinanceTransaction transaction);
    void softDelete(UUID id);
    void softDeleteByCreatedBy(UUID userId); // 회원 탈퇴 시 내가 입력한 거래만
    void reassignGroup(UUID fromGroupId, UUID toGroupId, UUID createdBy); // 그룹 이탈 시 개인 그룹으로 이관
}
