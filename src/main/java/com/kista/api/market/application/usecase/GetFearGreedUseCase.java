package com.kista.api.market.application.usecase;

import com.kista.api.market.domain.model.FearGreedSnapshot;

import java.util.List;

public interface GetFearGreedUseCase {

    // 지정 source의 최근 days일 스냅샷을 날짜 오름차순으로 조회
    List<FearGreedSnapshot> getRecent(String source, int days);
}
