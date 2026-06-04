package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.FidaOrderResponse;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class FidaOrderController {

    private final ExecuteFidaOrderUseCase executeFidaOrderUseCase;

    @Operation(summary = "FIDA 주문 실행", description = "FIDA 계좌로 즉시 지정가 매매 주문 접수. X-Internal-Token 헤더 필수.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "신규 저장 성공"),
            @ApiResponse(responseCode = "200", description = "기존 동일 데이터 존재 — 멱등 처리"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "내부 토큰 인증 실패"),
            @ApiResponse(responseCode = "409", description = "같은 날짜/종목에 내용이 다른 데이터 존재")
    })
    @PostMapping("/fida-orders")
    public ResponseEntity<FidaOrderResponse> placeFidaOrder(@RequestBody @Valid FidaOrderCommand command) {
        PrivacyTradeSaveResult result = executeFidaOrderUseCase.execute(command);
        FidaOrderResponse body = FidaOrderResponse.of(result.id(), command);
        return result.created()
                ? ResponseEntity.status(HttpStatus.CREATED).body(body)
                : ResponseEntity.ok(body);
    }
}
