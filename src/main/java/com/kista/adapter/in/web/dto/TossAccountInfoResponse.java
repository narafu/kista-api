package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossAccountInfo;

import java.util.List;

public record TossAccountInfoResponse(
    int    accountSeq, // 계좌 일련번호
    String accountNo   // 계좌번호
) {
    public static TossAccountInfoResponse from(TossAccountInfo info) {
        return new TossAccountInfoResponse(info.accountSeq(), info.accountNo());
    }

    public static List<TossAccountInfoResponse> fromList(List<TossAccountInfo> list) {
        return list.stream().map(TossAccountInfoResponse::from).toList();
    }
}
