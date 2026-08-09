package com.kista.domain.port.out;

import com.kista.domain.model.asset.Asset;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface AssetPort {
    List<Asset> findByUserId(UUID userId);
    Optional<Asset> findById(UUID id);

    // 없으면 NoSuchElementException — findById + orElseThrow 반복 제거용
    default Asset findByIdOrThrow(UUID assetId) {
        return findById(assetId).orElseThrow(
                () -> new NoSuchElementException("자산 기록을 찾을 수 없습니다: " + assetId));
    }

    // 자산 조회 + 소유권 검증 — 불일치 시 SecurityException (컨트롤러에서 403 변환)
    default Asset requireOwnedAsset(UUID assetId, UUID requesterId) {
        Asset asset = findByIdOrThrow(assetId);
        asset.verifyOwnedBy(requesterId);
        return asset;
    }

    Asset save(Asset asset);
    void delete(UUID id);
    void deleteByUserId(UUID userId); // 사용자 탈퇴 시 일괄 소프트 삭제
}
