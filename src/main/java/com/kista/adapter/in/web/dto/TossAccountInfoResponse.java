package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossAccountInfo;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record TossAccountInfoResponse(
    @Schema(description = "계좌 일련번호 (brokerAccountCode에 저장되는 값)")
    int    accountSeq, // 계좌 일련번호
    @Schema(description = "계좌번호 (마스킹 포함 가능)")
    String accountNo   // 계좌번호
) {
    public static TossAccountInfoResponse from(TossAccountInfo info) {
        return new TossAccountInfoResponse(info.accountSeq(), info.accountNo());
    }

    public static List<TossAccountInfoResponse> fromList(List<TossAccountInfo> list) {
        return list.stream().map(TossAccountInfoResponse::from).toList();
    }
}
