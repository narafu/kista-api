package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.FearGreedResponse;
import com.kista.domain.port.in.GetFearGreedUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "공포탐욕지수", description = "CNN·크립토 Fear & Greed Index 조회")
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class FearGreedController {

    private static final String SOURCE_CNN = "CNN";
    private static final String SOURCE_CRYPTO = "CRYPTO";

    private final GetFearGreedUseCase getFearGreedUseCase;

    @Operation(summary = "CNN·크립토 공포탐욕지수 조회 (현재값 + 추이)")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/fear-greed")
    public FearGreedResponse getFearGreed(
            @Parameter(description = "조회 기간(일)", example = "200") @RequestParam(defaultValue = "200") int days) {
        // 두 소스를 각각 조회해 번들 응답으로 조립
        return FearGreedResponse.from(
                getFearGreedUseCase.getRecent(SOURCE_CNN, days),
                getFearGreedUseCase.getRecent(SOURCE_CRYPTO, days));
    }
}
