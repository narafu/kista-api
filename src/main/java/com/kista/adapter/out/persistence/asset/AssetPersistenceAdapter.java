package com.kista.adapter.out.persistence.asset;

import com.kista.domain.model.asset.Asset;
import com.kista.domain.port.out.AssetPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AssetPersistenceAdapter implements AssetPort {

    private final AssetJpaRepository jpaRepository;

    @Override
    public List<Asset> findByUserId(UUID userId) {
        return jpaRepository.findByUserIdOrderByEntryDateDescIdDesc(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<Asset> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Asset save(Asset asset) {
        AssetEntity entity = toEntity(asset);
        AssetEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.softDeleteById(id, Instant.now());
    }

    @Override
    public void deleteByUserId(UUID userId) {
        jpaRepository.softDeleteByUserId(userId, Instant.now());
    }

    private AssetEntity toEntity(Asset a) {
        AssetEntity e = new AssetEntity();
        e.setId(a.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setUserId(a.userId());
        e.setEntryDate(a.entryDate());
        e.setCategory(a.category());
        e.setSubcategory(a.subcategory());
        e.setInstitution(a.institution());
        e.setAssetClass(a.assetClass());
        e.setStrategy(a.strategy());
        e.setAmount(a.amount());
        return e;
    }

    private Asset toDomain(AssetEntity e) {
        return new Asset(
                e.getId(), e.getUserId(), e.getEntryDate(), e.getCategory(),
                e.getSubcategory(), e.getInstitution(), e.getAssetClass(), e.getStrategy(),
                e.getAmount(), e.getCreatedAt()
        );
    }
}
