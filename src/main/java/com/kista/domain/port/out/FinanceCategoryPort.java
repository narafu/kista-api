package com.kista.domain.port.out;

import com.kista.domain.model.finance.FinanceCategory;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface FinanceCategoryPort {
    // 폼 선택지용 — 활성 행만(시스템 전역 + 해당 그룹), type 지정 시 필터링. §4.2의 (A) 읽기 모드.
    List<FinanceCategory> findSelectableByGroup(UUID groupId, FinanceCategory.Type type);

    // 삭제 여부 무관 조회 — 과거 거래·자산 기록의 카테고리 이름 렌더링 전용. 수정 같은 쓰기 경로에서
    // 쓰면 안 됨(FinanceCategory에 deletedAt 필드가 없어 save()가 merge 시 삭제 상태를 조용히
    // 되살릴 수 있다) — 그런 경로는 findActiveByIdOrThrow를 쓴다. §4.2의 (B) 읽기 모드.
    Optional<FinanceCategory> findById(UUID id);
    Optional<FinanceCategory> findActiveById(UUID id);

    default FinanceCategory findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("카테고리를 찾을 수 없습니다: " + id));
    }

    default FinanceCategory findActiveByIdOrThrow(UUID id) {
        return findActiveById(id).orElseThrow(
                () -> new NoSuchElementException("카테고리를 찾을 수 없습니다: " + id));
    }

    FinanceCategory save(FinanceCategory category);

    // 소프트 삭제 — 모든 하위 세대(임의 depth)도 함께 소프트 삭제
    void softDeleteWithChildren(UUID id);

    void softDeleteByCreatedBy(UUID userId); // 회원 탈퇴 시 내가 만든 그룹 카테고리만 (시스템은 createdBy null이라 자동 제외)

    void reassignGroup(UUID fromGroupId, UUID toGroupId, UUID createdBy); // 그룹 이탈 시 개인 그룹으로 이관
}
