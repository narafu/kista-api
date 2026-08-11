package com.kista.application.service.asset;

import com.kista.domain.model.asset.AssetMonthlyCheck;
import com.kista.domain.port.in.AssetMonthlyCheckUseCase;
import com.kista.domain.port.out.AssetMonthlyCheckPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
class AssetMonthlyCheckService implements AssetMonthlyCheckUseCase {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AssetMonthlyCheckPort assetMonthlyCheckPort;

    @Override
    @Transactional(readOnly = true)
    public List<AssetMonthlyCheck> listByUser(UUID userId) {
        return assetMonthlyCheckPort.findByUserId(userId);
    }

    @Override
    public AssetMonthlyCheck setCompleted(UUID userId, String month, boolean completed) {
        // 형식·범위(월 01~12) 동시 검증 — 실패 시 DateTimeParseException → GlobalExceptionHandler가 400으로 매핑
        YearMonth.parse(month, MONTH_FORMATTER);
        return assetMonthlyCheckPort.upsert(userId, month, completed);
    }
}
