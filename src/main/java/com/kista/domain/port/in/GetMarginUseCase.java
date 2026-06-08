package com.kista.domain.port.in;

import com.kista.domain.model.kis.MarginItem;

import java.util.List;
import java.util.UUID;

// KIS TTTC2101R — 해외증거금 통화별 조회 (USD·KRW)
public interface GetMarginUseCase {
    List<MarginItem> getMargin(UUID accountId, UUID requesterId);
}
