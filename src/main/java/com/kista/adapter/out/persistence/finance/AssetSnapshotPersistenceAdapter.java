package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.AssetSnapshot;
import com.kista.domain.port.out.AssetSnapshotPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AssetSnapshotPersistenceAdapter implements AssetSnapshotPort {

    private final FinanceAssetSnapshotJpaRepository jpaRepository;

    @Override
    public List<AssetSnapshot> findByGroupId(UUID groupId, LocalDate from, LocalDate to, UUID createdBy) {
        return jpaRepository.findByGroupId(groupId, from, to, createdBy).stream()
                .map(FinanceAssetSnapshotEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<AssetSnapshot> findById(UUID id) {
        return jpaRepository.findById(id).map(FinanceAssetSnapshotEntity::toDomain);
    }

    @Override
    public AssetSnapshot save(AssetSnapshot snapshot) {
        FinanceAssetSnapshotEntity entity = FinanceAssetSnapshotEntity.fromModel(snapshot);
        return FinanceAssetSnapshotEntity.toDomain(jpaRepository.save(entity));
    }

    @Override
    public void softDelete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }

    @Override
    public void softDeleteByCreatedBy(UUID userId) {
        jpaRepository.softDeleteByCreatedBy(userId, Instant.now());
    }

    @Override
    public void reassignGroup(UUID fromGroupId, UUID toGroupId, UUID createdBy) {
        jpaRepository.reassignGroup(fromGroupId, toGroupId, createdBy);
    }
}
