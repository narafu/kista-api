package com.kista.privacy.adapter.in.web;

import com.kista.privacy.application.usecase.PrivacyUseCase;
import com.kista.privacy.domain.model.PrivacyTradeBaseView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

// admin의 PrivacyQueryHttpAdapter가 소비하는 내부 전용 읽기 엔드포인트 — X-Internal-Token 인증
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/privacy")
@RequiredArgsConstructor
public class PrivacyInternalQueryController {

    private final PrivacyUseCase privacy;

    @Operation(summary = "기준 매매표 조회", description = "admin 조회 전용. X-Internal-Token 필수.")
    @GetMapping("/trade-bases")
    public List<PrivacyTradeBaseView> listTradeBases(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromReleaseDate) {
        return privacy.findBasesFromTradeDate(fromReleaseDate);
    }
}
