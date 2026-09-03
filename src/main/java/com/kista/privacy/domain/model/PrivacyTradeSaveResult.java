package com.kista.privacy.domain.model;

import java.util.UUID;

public record PrivacyTradeSaveResult(
        UUID id,
        boolean created  // true = 신규 저장, false = 기존 동일 데이터
) {}
