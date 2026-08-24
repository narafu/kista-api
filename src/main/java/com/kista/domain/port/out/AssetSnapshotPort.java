package com.kista.domain.port.out;

import com.kista.domain.model.finance.AssetSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface AssetSnapshotPort {
    // from/to/filterUserId는 선택적 필터 — null이면 무시. entryDate 최신순. currentGroupId는 무그룹 유저면 null.
    List<AssetSnapshot> findMyScope(UUID userId, UUID currentGroupId, LocalDate from, LocalDate to, UUID filterUserId);

    Optional<AssetSnapshot> findById(UUID id);

    default AssetSnapshot findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("자산 기록을 찾을 수 없습니다: " + id));
    }

    AssetSnapshot save(AssetSnapshot snapshot);
    boolean existsByAccountId(UUID accountId);
    void softDelete(UUID id);
    void softDeleteByUserId(UUID userId); // 회원 탈퇴 시 내가 입력한 스냅샷만
}
