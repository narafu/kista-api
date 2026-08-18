package com.kista.domain.port.in;

import com.kista.domain.model.finance.AssetSnapshot;
import com.kista.domain.model.finance.AssetSnapshotCommand;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AssetSnapshotUseCase {
    List<AssetSnapshot> list(UUID userId, UUID requestedGroupId, LocalDate from, LocalDate to, UUID createdBy);
    AssetSnapshot create(UUID userId, UUID requestedGroupId, AssetSnapshotCommand command);
    AssetSnapshot update(UUID snapshotId, UUID userId, AssetSnapshotCommand command);
    void delete(UUID snapshotId, UUID userId);
}
