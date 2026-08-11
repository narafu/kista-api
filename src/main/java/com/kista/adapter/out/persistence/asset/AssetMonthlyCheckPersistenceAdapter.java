package com.kista.adapter.out.persistence.asset;

import com.kista.domain.model.asset.AssetMonthlyCheck;
import com.kista.domain.port.out.AssetMonthlyCheckPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AssetMonthlyCheckPersistenceAdapter implements AssetMonthlyCheckPort {

    private final AssetMonthlyCheckJpaRepository jpaRepository;

    @Override
    public List<AssetMonthlyCheck> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).stream()
                .map(e -> new AssetMonthlyCheck(e.getMonth(), e.isCompleted()))
                .toList();
    }

    @Override
    public AssetMonthlyCheck upsert(UUID userId, String month, boolean completed) {
        jpaRepository.upsert(userId, month, completed);
        return new AssetMonthlyCheck(month, completed);
    }

    @Override
    public void deleteByUserId(UUID userId) {
        jpaRepository.deleteByUserId(userId);
    }
}
