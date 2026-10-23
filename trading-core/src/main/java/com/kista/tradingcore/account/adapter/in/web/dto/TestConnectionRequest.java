package com.kista.tradingcore.account.adapter.in.web.dto;

import com.kista.shared.kernel.Broker;
import io.swagger.v3.oas.annotations.media.Schema;

// 증권사 연결 테스트 요청 body
public record TestConnectionRequest(
        @Schema(description = "증권사", example = "KIS")
        Broker broker,
        @Schema(description = "증권사 앱 키 (신규 자격증명 테스트 시)")
        String appKey,
        @Schema(description = "증권사 앱 시크릿 (신규 자격증명 테스트 시)")
        String appSecret) {}
