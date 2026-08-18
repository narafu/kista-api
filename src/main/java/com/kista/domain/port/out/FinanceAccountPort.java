package com.kista.domain.port.out;

import com.kista.domain.model.finance.FinanceAccount;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface FinanceAccountPort {
    List<FinanceAccount> findByGroupId(UUID groupId);
    Optional<FinanceAccount> findById(UUID id);

    default FinanceAccount findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("계좌를 찾을 수 없습니다: " + id));
    }

    FinanceAccount save(FinanceAccount account);
    void softDelete(UUID id);
    void softDeleteByCreatedBy(UUID userId); // 회원 탈퇴 시 내가 만든 계좌만
    void reassignGroup(UUID fromGroupId, UUID toGroupId, UUID createdBy); // 그룹 이탈 시 개인 그룹으로 이관
}
