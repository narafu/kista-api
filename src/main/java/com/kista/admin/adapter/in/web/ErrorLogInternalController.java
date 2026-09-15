package com.kista.admin.adapter.in.web;

import com.kista.admin.adapter.in.web.dto.ErrorLogRequest;
import com.kista.admin.application.port.output.AppErrorLogPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// TradingExceptionHandler(trading-core)가 KisApiException/TossApiException 발생 시 호출하는
// 역방향 내부 API — 이 계획의 다른 내부 API는 전부 root->trading-core였으나, app_error_logs가
// root 소유 테이블이라 방향이 반대다
@Slf4j
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/errors")
@RequiredArgsConstructor
public class ErrorLogInternalController {

    private final AppErrorLogPort appErrorLogPort;

    // 저장 실패가 trading-core 응답 처리를 막지 않도록 격리(ClientErrorLogController와 동일 패턴)
    @Operation(summary = "trading-core 오류 리포트 저장", description = "app_error_logs에 저장합니다. X-Internal-Token 필수.")
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@RequestBody ErrorLogRequest request) {
        try {
            appErrorLogPort.save(request.errorType(), request.message(), request.stackTrace(), request.context());
        } catch (Exception saveEx) {
            log.warn("trading-core 오류 로그 저장 실패: {}", saveEx.getMessage());
        }
    }
}
