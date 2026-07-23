package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.ClientErrorLogRequest;
import com.kista.domain.port.out.AppErrorLogPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "ClientErrorLog", description = "kista-ui 오류 바운더리 리포트")
@RestController
@RequestMapping("/api/client-errors")
@RequiredArgsConstructor
public class ClientErrorLogController {

    private final AppErrorLogPort appErrorLogPort;

    // 비인증 허용 — 로그인 전 화면(error.tsx/global-error.tsx)에서도 오류 리포트 필요
    @Operation(summary = "클라이언트 오류 리포트", description = "브라우저에서 발생한 미처리 오류를 app_error_logs에 저장합니다.")
    @ApiResponse(responseCode = "204", description = "저장 성공(실패해도 항상 204 — 리포트 실패가 사용자 경험에 영향을 주지 않도록 격리)")
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void log(@Valid @RequestBody ClientErrorLogRequest body) {
        try {
            appErrorLogPort.save(body.errorType(), body.message(), body.stackTrace(), body.context());
        } catch (Exception saveEx) {
            log.warn("클라이언트 오류 로그 저장 실패: {}", saveEx.getMessage());
        }
    }
}
