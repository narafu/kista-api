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
    public List<AssetSnapshot> findMyScope(UUID userId, UUID currentGroupId, LocalDate from, LocalDate to, UUID filterUserId) {
        return jpaRepository.findMyScope(userId, currentGroupId, from, to, filterUserId).stream()
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
    public boolean existsByAccountId(UUID accountId) {
        return jpaRepository.existsByAccountIdAndDeletedAtIsNull(accountId);
    }

    @Override
    public void softDelete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }

    @Override
    public void softDeleteByUserId(UUID userId) {
        jpaRepository.softDeleteByUserId(userId, Instant.now());
    }
}
