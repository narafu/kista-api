package com.kista.tradingcore.broker.adapter.out.meritz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CancelOutcome;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import com.kista.tradingcore.broker.domain.model.meritz.MeritzApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

// 메리츠 해외주식 주문 접수·취소 — 경고(warn_cls_code 1/3)는 warn_cnfr_yn=Y로 1회 재전송, 그 외 자동 재시도 없음
@Slf4j
@Component
@RequiredArgsConstructor
class MeritzOrderApi {

    private static final String BUY_PATH = "/trading/v1/overseas/orders/buy";
    private static final String SELL_PATH = "/trading/v1/overseas/orders/sell";
    private static final String CANCEL_PATH = "/trading/v1/overseas/orders/cancel";
    private static final String TODAY_PATH = "/trading/v1/overseas/orders/today";
    private static final String HISTORY_PATH = "/trading/v1/overseas/orders/history";
    private static final String MARKET_PRICE = "0"; // 시장가 계열 가격

    private final MeritzHttpClient http;
    private final MeritzExchangeRegistry exchangeRegistry;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderResponse(
            @JsonProperty("oder_no") String orderNo,         // 주문번호(숫자) — 0/없음이면 결과 불명
            @JsonProperty("warn_cls_code") String warnCode,  // 0 접수 / 1 경고 / 2 보류 / 3 PTP 확인
            @JsonProperty("warn_msg") String warnMsg) {}

    // 당일·기간 주문체결 행 — 당일과 기간 목록은 수량 필드명이 다르다
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderRow(
            @JsonProperty("oder_date") String orderDate,         // US 거래일 yyyyMMdd — 취소 요청에 그대로 전달
            @JsonProperty("oder_no") String orderNo,
            @JsonProperty("aspr_type_code") String asprType,     // 호가유형 — 취소 oder_cls_code 역매핑 원본
            @JsonProperty("odqt") String todayOrdered,           // 당일: 주문수량
            @JsonProperty("cntg_qty") String todayFilled,        // 당일: 체결수량
            @JsonProperty("oder_able_qty") String todayCancelable, // 당일: 정정·취소 가능 수량
            @JsonProperty("oder_dqty") String histOrdered,       // 기간: 주문수량
            @JsonProperty("cntg_dqty") String histFilled,        // 기간: 누적 체결수량
            @JsonProperty("ncnt_odqt") String histCancelable) {} // 기간: 미체결(취소 가능) 수량

    // 정규화된 원주문 — 출처(당일/기간)와 무관
    private record Original(String orderDate, String asprType, int ordered, int filled, int cancelable) {}

    OrderResult place(OrderInstruction instruction, BrokerAccountRef account) {
        boolean buy = instruction.direction() == OrderDirection.BUY;
        // 수량 누락은 송신 전 차단 — "null" 문자열 전송 방지
        if (instruction.quantity() == null || instruction.quantity() <= 0) {
            throw new IllegalArgumentException("Meritz 주문 수량이 없습니다: " + instruction.quantity());
        }
        // 유형 → [oder_cls_code, oder_cond_cls_code, 가격] — 지정가 계열만 단가, 시장가 매도만 "0"
        BigDecimal p = instruction.price();
        String[] m = switch (instruction.orderType()) {
            case LOC -> new String[]{"32", "7", formatPrice(p)};                                      // LOC
            case MOC -> buy ? new String[]{"32", "7", formatPrice(p)} : new String[]{"34", "7", MARKET_PRICE}; // 매수 MOC는 LOC 캡
            case LIMIT -> new String[]{"01", "0", formatPrice(p)};                                    // 지정가
            case MOO -> buy ? new String[]{"01", "0", formatPrice(p)} : new String[]{"05", "0", MARKET_PRICE}; // 개장 후 지정가 근사 / 시장가 매도
            case MARKET -> throw new IllegalArgumentException("MARKET 주문은 코인 거래소 전용입니다"); // 주식 경로 도달 불가 방어
        };
        Map<String, String> body = new LinkedHashMap<>();
        body.put("ovrs_stck_symb_iscd", instruction.ticker().name());
        body.put("ovrs_exch_vndr_idno", exchangeRegistry.vendorId(instruction.ticker()));
        body.put("oder_cls_code", m[0]);
        body.put("oder_cond_cls_code", m[1]);
        body.put("odqt", String.valueOf(instruction.quantity()));
        body.put("ovrs_stck_oder_prc", m[2]);
        OrderResponse res = postConfirmingWarning(buy ? BUY_PATH : SELL_PATH, account, body);
        long orderNo = parseOrderNo(res.orderNo());
        if (orderNo <= 0) {
            throw new MeritzApiException(null, "Meritz 주문 응답 주문번호 없음/해석 불가(" + res.orderNo() + ") — 접수 여부 불명",
                    null, false);
        }
        return new OrderResult(String.valueOf(orderNo));
    }

    CancelOutcome cancel(CancelInstruction instruction, BrokerAccountRef account) {
        Original o = findOriginal(instruction.externalOrderId(), account);
        if (o.ordered() <= 0) {
            // 주문수량 누락 행 — 체결 완료로 오판해 CANCELLED/FILLED 오기록하지 않도록 결과 불명
            throw new MeritzApiException(null, "Meritz 원주문 수량 해석 불가: oder_no=" + instruction.externalOrderId(),
                    null, false);
        }
        if (o.cancelable() <= 0) {
            // 이미 종결(체결 완료 또는 취소·장 마감 만료) — 취소 API를 부르면 업무 오류로 실패가 보이므로 호출하지 않는다
            return o.filled() == o.ordered() ? CancelOutcome.ALREADY_FILLED : CancelOutcome.ALREADY_CANCELED;
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("oder_date", o.orderDate());
        body.put("orgl_oder_no", instruction.externalOrderId());
        body.put("oder_cls_code", cancelOrderClass(o.asprType()));
        body.put("odqt", String.valueOf(o.cancelable())); // 잔량 전부 취소
        postConfirmingWarning(CANCEL_PATH, account, body);
        return CancelOutcome.CANCELLED;
    }

    // 경고 1/3은 미접수 확정이라 warn_cnfr_yn=Y로 1회 재전송(중복 주문 아님), 그 외 비접수는 확정 거절
    private OrderResponse postConfirmingWarning(String path, BrokerAccountRef account, Map<String, String> body) {
        OrderResponse res = send(path, account, body);
        String code = res.warnCode();
        if ("1".equals(code) || "3".equals(code)) {
            log.warn("Meritz POST {} 경고 자동 확인 후 재전송: warn_cls_code={}, warn_msg={}, accountId={}",
                    path, code, res.warnMsg(), account.id());
            Map<String, String> confirmed = new LinkedHashMap<>(body);
            confirmed.put("warn_cnfr_yn", "Y");
            res = send(path, account, confirmed);
            code = res.warnCode();
        }
        // warn_cls_code 누락은 경고 없음으로 보고 주문번호 판정에 맡긴다(없으면 결과 불명)
        if (code != null && !"0".equals(code)) {
            throw MeritzApiException.rejected("WARN_" + code, "Meritz POST " + path + " 미접수: warn_cls_code=" + code + ", " + res.warnMsg());
        }
        return res;
    }

    private OrderResponse send(String path, BrokerAccountRef account, Map<String, String> body) {
        OrderResponse res = http.rows(http.post(path, account, body), OrderResponse.class);
        if (res == null) {
            throw new MeritzApiException(null, "Meritz POST " + path + " 응답 data 없음 — 접수 여부 불명", null, false);
        }
        return res;
    }

    // 당일 목록 → 없으면 최근 기간 목록 → 그래도 없으면 결과 불명(접수 직후 목록 지연 가능 — 이미 취소로 단정하지 않음)
    private Original findOriginal(String orderNo, BrokerAccountRef account) {
        MultiValueMap<String, String> todayParams = new LinkedMultiValueMap<>();
        todayParams.add("cntg_cls_code", "0");   // 전체(체결·미체결)
        todayParams.add("seln_byov_clcd", "0");  // 매수·매도 전체
        // 당일 목록도 5766 완료 코드 계열 — 5762면 이어 조회(없으면 1회 GET과 같다)
        for (OrderRow r : http.getAllPages(TODAY_PATH, account, todayParams, OrderRow.class)) {
            if (sameOrderNo(r.orderNo(), orderNo)) {
                return new Original(r.orderDate(), r.asprType(), qty(r.todayOrdered()), qty(r.todayFilled()), qty(r.todayCancelable()));
            }
        }
        // 검색 범위일 뿐 거래일 변환이 아니다 — KST 오늘 기준 앞뒤 여유를 둬 US 날짜 경계를 덮는다
        LocalDate today = LocalDate.now(TimeZones.KST);
        MultiValueMap<String, String> histParams = new LinkedMultiValueMap<>();
        histParams.add("oder_sta_date", today.minusDays(8).format(DateTimeFormatter.BASIC_ISO_DATE));
        histParams.add("oder_fin_date", today.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE));
        histParams.add("cntg_cls_code", "0");
        for (OrderRow r : http.getAllPages(HISTORY_PATH, account, histParams, OrderRow.class)) {
            if (sameOrderNo(r.orderNo(), orderNo)) {
                return new Original(r.orderDate(), r.asprType(), qty(r.histOrdered()), qty(r.histFilled()), qty(r.histCancelable()));
            }
        }
        throw new MeritzApiException(null, "원주문 조회 실패", null, false);
    }

    // oder_no는 JSON 숫자 — 숫자 값으로 비교(선행 0·공백 차이 무시)
    private static boolean sameOrderNo(String rowNo, String externalOrderId) {
        long target = parseOrderNo(externalOrderId);
        return target > 0 && parseOrderNo(rowNo) == target;
    }

    // 주문번호 숫자 해석 — 없음·비숫자는 -1
    private static long parseOrderNo(String s) {
        if (s == null || s.isBlank()) return -1;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    private static int qty(String s) {
        return s == null || s.isBlank() ? 0 : MeritzResponse.wholeQuantity(s);
    }

    // 목록의 aspr_type_code → 취소 요청 oder_cls_code (원주문 유형 그대로)
    static String cancelOrderClass(String asprType) {
        return switch (asprType == null ? "" : asprType.trim()) {
            case "1" -> "05"; // 시장가
            case "2" -> "01"; // 지정가
            case "4" -> "31"; // LOO
            case "5" -> "32"; // LOC
            case "6" -> "33"; // MOO
            case "7" -> "34"; // MOC
            case "8" -> "35"; // Limit VWAP
            case "9" -> "36"; // Limit TWAP
            case "A" -> "37"; // VWAP
            case "B" -> "38"; // TWAP
            default -> throw MeritzApiException.rejected(null, "Meritz 취소 불가 — 알 수 없는 aspr_type_code: " + asprType);
        };
    }

    // 소수 2자리 반올림 — 메리츠 한도(4자리) 이내
    // 가격 지정 주문의 단가 누락은 송신 전 차단 — "0"으로 보내면 사실상 무가격 주문이 된다
    private static String formatPrice(BigDecimal price) {
        if (price == null) throw new IllegalArgumentException("Meritz 지정가 계열 주문 단가가 없습니다");
        return price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
