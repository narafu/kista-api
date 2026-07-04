package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;

import java.util.UUID;

// 증권사 연결 테스트 요청 body
public record TestConnectionRequest(Account.Broker broker, String appKey, String appSecret, UUID accountId) {}
