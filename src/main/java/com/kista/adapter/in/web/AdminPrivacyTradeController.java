package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminPrivacyBaseResponse;
import com.kista.domain.port.in.AdminQueryUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/privacy-trade-bases")
@RequiredArgsConstructor
public class AdminPrivacyTradeController {

    private final AdminQueryUseCase adminQuery;

    // PRIVACY 기준 매매표(master) + 주문 명세(detail) 목록 — range 미전달 시 전체, 최소 30
    @GetMapping
    public List<AdminPrivacyBaseResponse> listBases(@RequestParam(required = false) Integer range) {
        if (range != null && range < 30)
            throw new IllegalArgumentException("range는 30 이상이어야 합니다");
        return adminQuery.listPrivacyBases(range).stream()
                .map(AdminPrivacyBaseResponse::from)
                .toList();
    }

}
