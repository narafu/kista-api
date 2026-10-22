package com.kista.tradingcore.broker.adapter.out.meritz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import com.kista.tradingcore.broker.domain.model.meritz.MeritzApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

// 메리츠 공통 응답 봉투 — 성공/실패는 HTTP 상태가 아니라 rsp_cd로만 판정(업무 오류도 HTTP 500으로 온다)
@JsonIgnoreProperties(ignoreUnknown = true)
record MeritzResponse(
        @JsonProperty("rsp_cd") String rspCd,           // 응답 코드
        @JsonProperty("rsp_msg") String rspMsg,         // 응답 메시지(로그용)
        @JsonProperty("rsp_sub_msg") String rspSubMsg,  // 보조 메시지(로그용)
        @JsonProperty("tr_cont") String trCont,         // "1"이면 다음 페이지 있음
        @JsonProperty("tr_cont_key") String trContKey,  // 연속조회 키 — 오른쪽 공백 포함 원문 그대로 재전송
        @JsonProperty("data") JsonNode data             // 행 배열 또는 단건 객체
) {

    // 실측 성공 코드: 0000 정상, 0001 주문 접수, 5620/5820/5822 조회(자료 유무는 data로), 5762 연속조회 계속, 5766 조회 완료
    static final Set<String> SUCCESS_CODES = Set.of("0000", "0001", "5620", "5762", "5766", "5820", "5822");
    static final Set<String> TOKEN_INVALID_CODES = Set.of("EGW00121", "EGW00123"); // 토큰 무효·만료 — 재발급 후 1회 재시도
    static final String RATE_LIMITED = "EGW00200";                                 // 초당 호출 한도(API별 10 TPS)
    static final String INVALID_CLIENT = "EGW00103";                               // 잘못된 client_id
    static final Set<String> PERMISSION_DENIED_CODES = Set.of("0760", "EGW00202"); // 권한 없음

    boolean success() {
        return SUCCESS_CODES.contains(rspCd);
    }

    boolean hasNextPage() {
        return "5762".equals(rspCd) && trContKey != null && !trContKey.isEmpty();
    }

    // 본문 파싱 — 빈 본문·비JSON·rsp_cd 없음은 게이트웨이 장애로 보고 결과 불명(rejected=false)
    static MeritzResponse parse(ObjectMapper objectMapper, String body, String action) {
        MeritzResponse res = null;
        if (body != null && !body.isBlank()) {
            try {
                res = objectMapper.readValue(body, MeritzResponse.class);
            } catch (RuntimeException _) {
                // 아래에서 결과 불명 처리
            }
        }
        if (res == null || res.rspCd() == null) {
            throw new MeritzApiException(null, "Meritz " + action + " 응답 본문 해석 불가: " + abbreviate(body),
                    null, MeritzApiException.Conflict.NONE, false);
        }
        return res;
    }

    // 비성공 rsp_cd → 예외 (토큰 무효·한도는 호출부가 먼저 처리)
    RuntimeException failure(String action) {
        if (INVALID_CLIENT.equals(rspCd)) return new BrokerCredentialException();
        if (PERMISSION_DENIED_CODES.contains(rspCd)) return new BrokerPermissionDeniedException();
        return MeritzApiException.rejected(rspCd, "Meritz " + action + " 실패: rsp_cd=" + rspCd + ", " + rspMsg + " / " + rspSubMsg);
    }

    // 숫자 문자열/숫자 혼재 필드 — 행 record는 String으로 받고 여기서 변환
    static BigDecimal decimal(String s) {
        return s == null || s.isBlank() ? null : new BigDecimal(s.trim());
    }

    // "417.0000000000" 같은 소수 표기 정수 수량
    static int wholeQuantity(String s) {
        return new BigDecimal(s.trim()).setScale(0, RoundingMode.DOWN).intValueExact();
    }

    private static String abbreviate(String body) {
        if (body == null) return "null";
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
