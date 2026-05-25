package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.PrivacyCurrentBaseResponse;
import com.kista.domain.port.in.GetPrivacyCurrentBaseUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "PRIVACY 매매표", description = "PRIVACY 전략 기준 매매표 조회")
@RestController
@RequestMapping("/api/privacy-trades")
@RequiredArgsConstructor
public class PrivacyTradeController {

    private final GetPrivacyCurrentBaseUseCase getPrivacyCurrentBaseUseCase;

    @Operation(summary = "현재 사이클 기준가 조회",
               description = "오늘 이후 거래일 중 가장 미래의 privacy_trades_master 행에서 current_cycle_start를 반환. 전략 등록 시 매수 배수 MAX 계산에 사용.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "오늘 이후 날짜의 기준 매매표 없음")
    })
    @GetMapping("/base/latest")
    public PrivacyCurrentBaseResponse getLatestBase() {
        // GlobalExceptionHandler가 NoSuchElementException → 404 처리
        return PrivacyCurrentBaseResponse.from(getPrivacyCurrentBaseUseCase.getPrivacyCurrentBase());
    }
}
