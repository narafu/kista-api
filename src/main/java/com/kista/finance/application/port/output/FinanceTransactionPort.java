package com.kista.finance.application.port.output;

import com.kista.finance.domain.model.FinanceTransaction;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface FinanceTransactionPort {
    // from/to/categoryId/filterUserId는 선택적 필터 — null이면 무시. currentGroupId는 무그룹 유저면 null(개인 데이터만 조회).
    List<FinanceTransaction> findMyScope(UUID userId, UUID currentGroupId, LocalDate from, LocalDate to, UUID categoryId, UUID filterUserId);

    Optional<FinanceTransaction> findById(UUID id);

    default FinanceTransaction findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("거래내역을 찾을 수 없습니다: " + id));
    }

    FinanceTransaction save(FinanceTransaction transaction);
    void softDelete(UUID id);
    void softDeleteByUserId(UUID userId); // 회원 탈퇴 시 내가 입력한 거래만
}
