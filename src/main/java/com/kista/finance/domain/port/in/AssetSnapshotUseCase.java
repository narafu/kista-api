package com.kista.finance.domain.port.in;

import com.kista.finance.domain.model.AssetSnapshot;
import com.kista.finance.domain.model.AssetSnapshotCommand;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AssetSnapshotUseCase {
    List<AssetSnapshot> list(UUID userId, UUID requestedGroupId, LocalDate from, LocalDate to, UUID createdBy);
    AssetSnapshot create(UUID userId, UUID requestedGroupId, AssetSnapshotCommand command);
    AssetSnapshot update(UUID snapshotId, UUID userId, AssetSnapshotCommand command);
    void delete(UUID snapshotId, UUID userId);

    // 개인 소유 자산 기록을 소유자의 현재 그룹으로 공유 전환한다.
    AssetSnapshot shareToGroup(UUID snapshotId, UUID userId);

    // 그룹 공유 자산 기록을 개인 소유로 되돌린다. 같은 그룹 멤버면 누구든 가능(소유자 한정 아님).
    AssetSnapshot unshare(UUID snapshotId, UUID userId);
}
