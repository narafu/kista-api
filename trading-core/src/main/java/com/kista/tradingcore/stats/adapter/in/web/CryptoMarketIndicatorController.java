package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.tradingcore.stats.adapter.in.web.dto.CryptoMarketIndicatorsResponse;
import com.kista.tradingcore.stats.application.usecase.CryptoMarketIndicatorUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@Tag(name = "코인 시장 지표", description = "김치 프리미엄·테더 프리미엄·펀딩비·미결제약정")
@RestController
@RequiredArgsConstructor
public class CryptoMarketIndicatorController {

    private static final CacheControl CACHE = CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic();

    private final CryptoMarketIndicatorUseCase useCase; // 코인 시장 지표 조회

    @Operation(summary = "코인 시장 지표 조회", description = "김프(공식 환율·테더 기준)·테더 프리미엄·펀딩비·미결제약정. 실패한 섹션은 null.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "days 범위 오류")
    @GetMapping("/api/crypto-market-indicators")
    public ResponseEntity<CryptoMarketIndicatorsResponse> getCryptoMarketIndicators(
            @Parameter(description = "조회 기간(일)", example = "200") @RequestParam(defaultValue = "200") int days) {
        return ResponseEntity.ok().cacheControl(CACHE).body(CryptoMarketIndicatorsResponse.from(useCase.get(days)));
    }
}
