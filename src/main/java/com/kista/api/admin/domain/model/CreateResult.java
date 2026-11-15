package com.kista.api.admin.domain.model;

import com.kista.shared.contract.privacy.PrivacyTradeBaseResponse;

// FIDA 수신 경로(executeFidaOrder) 재사용 — 동일 (releaseDate, ticker) 존재+내용 동일 시 created=false(멱등)
public record CreateResult(PrivacyTradeBaseResponse view, boolean created) {}
