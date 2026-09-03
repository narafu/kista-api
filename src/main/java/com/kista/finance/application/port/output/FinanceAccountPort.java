package com.kista.finance.application.port.output;

import com.kista.finance.domain.model.FinanceAccount;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface FinanceAccountPort {
    // currentGroupId는 무그룹 유저면 null(개인 계좌만 조회).
    List<FinanceAccount> findMyScope(UUID userId, UUID currentGroupId);
    // 삭제된 계좌도 조회됨 — 과거 자산 기록 렌더링(AssetSnapshotController.enrich) 전용. 수정·삭제 같은
    // 쓰기 경로에서 쓰면 안 됨(FinanceAccount에 deletedAt 필드가 없어 save()가 merge 시 삭제 상태를
    // 조용히 되살릴 수 있다) — 그런 경로는 findActiveByIdOrThrow를 쓴다.
    Optional<FinanceAccount> findById(UUID id);
    Optional<FinanceAccount> findActiveById(UUID id);

    default FinanceAccount findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("계좌를 찾을 수 없습니다: " + id));
    }

    default FinanceAccount findActiveByIdOrThrow(UUID id) {
        return findActiveById(id).orElseThrow(
                () -> new NoSuchElementException("계좌를 찾을 수 없습니다: " + id));
    }

    // 전역 계좌번호 중복 체크(크로스-유저, HMAC-SHA256 해시 기반). excludeId는 update 시 자기 자신 제외용, 신규 등록은 null.
    boolean existsByAccountNo(String accountNo, UUID excludeId);

    FinanceAccount save(FinanceAccount account);
    void softDelete(UUID id);
    void softDeleteByUserId(UUID userId); // 회원 탈퇴 시 내가 만든 계좌만
}
