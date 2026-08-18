package com.kista.domain.port.out;

import com.kista.domain.model.finance.AssetSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface AssetSnapshotPort {
    // from/to/createdBy는 선택적 필터 — null이면 무시. entryDate 최신순.
    List<AssetSnapshot> findByGroupId(UUID groupId, LocalDate from, LocalDate to, UUID createdBy);

    Optional<AssetSnapshot> findById(UUID id);

    default AssetSnapshot findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("자산 기록을 찾을 수 없습니다: " + id));
    }

    AssetSnapshot save(AssetSnapshot snapshot);
    void softDelete(UUID id);
    void softDeleteByCreatedBy(UUID userId); // 회원 탈퇴 시 내가 입력한 스냅샷만
    void reassignGroup(UUID fromGroupId, UUID toGroupId, UUID createdBy); // 그룹 이탈 시 개인 그룹으로 이관
}
