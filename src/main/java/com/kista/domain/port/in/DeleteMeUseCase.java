package com.kista.domain.port.in;

import java.util.UUID;

public interface DeleteMeUseCase {
    // 본인 계정 삭제 — cascade로 accounts/kis_tokens/trade_histories/portfolio_snapshots 자동 삭제
    void deleteMe(UUID userId);
}
