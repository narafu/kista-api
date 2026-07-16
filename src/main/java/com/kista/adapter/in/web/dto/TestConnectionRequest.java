package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 증권사 연결 테스트 요청 body
public record TestConnectionRequest(
        @Schema(description = "증권사", example = "KIS")
        Account.Broker broker,
        @Schema(description = "증권사 앱 키 (신규 자격증명 테스트 시)")
        String appKey,
        @Schema(description = "증권사 앱 시크릿 (신규 자격증명 테스트 시)")
        String appSecret,
        @Schema(description = "기존 계좌 ID (등록된 계좌로 테스트 시, 자격증명 생략 가능)")
        UUID accountId) {}
