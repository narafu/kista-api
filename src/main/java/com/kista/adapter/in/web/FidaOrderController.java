package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.FidaOrderResponse;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.FidaOrderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class FidaOrderController {

    private final ExecuteFidaOrderUseCase executeFidaOrderUseCase;

    @Operation(summary = "FIDA 주문 실행", description = "FIDA 계좌로 즉시 지정가 매매 주문 접수. X-Internal-Token 헤더 필수.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "주문 접수 성공 — 저장된 master record 반환"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "내부 토큰 인증 실패")
    })
    @PostMapping("/fida-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public FidaOrderResponse placeFidaOrder(@RequestBody @Valid FidaOrderRequest request) {
        return FidaOrderResponse.of(executeFidaOrderUseCase.execute(request), request);
    }
}
