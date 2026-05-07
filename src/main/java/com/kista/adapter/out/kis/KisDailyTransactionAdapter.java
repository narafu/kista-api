package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.Account;
import com.kista.domain.model.DailyTransaction;
import com.kista.domain.model.DailyTransactionResult;
import com.kista.domain.model.DailyTransactionSummary;
import com.kista.domain.model.Order;
import com.kista.domain.port.out.KisDailyTransactionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KisDailyTransactionAdapter implements KisDailyTransactionPort {

    private static final String PATH = "/uapi/overseas-stock/v1/trading/inquire-period-trans";
    private static final String TR_ID = "CTOS4001R"; // 해외주식 일별거래내역
    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisHttpClient kisHttpClient;

    @Override
    public DailyTransactionResult getDailyTransactions(LocalDate from, LocalDate to, Account account) {
        HttpHeaders headers = kisHttpClient.buildHeaders(TR_ID, account);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", account.accountNo());
        params.add("ACNT_PRDT_CD", account.kisAccountType());
        params.add("ERLM_STRT_DT", from.format(FMT)); // 등록시작일자
        params.add("ERLM_END_DT", to.format(FMT));     // 등록종료일자
        params.add("OVRS_EXCG_CD", "NASD");            // 미국 전체
        params.add("PDNO", "");                         // 전종목
        params.add("SLL_BUY_DVSN_CD", "00");           // 00=전체, 01=매도, 02=매수
        params.add("LOAN_DVSN_CD", "");
        params.add("CTX_AREA_FK100", "");
        params.add("CTX_AREA_NK100", "");

        TransactionResponse response = kisHttpClient.get(PATH, headers, params, TransactionResponse.class);

        if (response == null) {
            return new DailyTransactionResult(Collections.emptyList(), emptySummary());
        }

        List<DailyTransaction> items = response.output1() == null
                ? Collections.emptyList()
                : response.output1().stream()
                        .map(o -> new DailyTransaction(
                                o.tradDt(),
                                o.sttlDt(),
                                parseDirection(o.sllBuyDvsnCd()),
                                o.pdno(),
                                o.ovrsItemName(),
                                parseIntSafe(o.ccldQty()),
                                parseBd(o.ovrsStckCcldUnpr()),
                                parseBd(o.trFrcrAmt2()),
                                parseBd(o.wcrcExccAmt()),
                                parseBd(o.erlmExrt()),
                                o.crcyCd()
                        ))
                        .toList();

        DailyTransactionSummary summary = emptySummary();
        if (response.output2() != null) {
            summary = new DailyTransactionSummary(
                    parseBd(response.output2().frcrBuyAmtSmtl()),
                    parseBd(response.output2().frcrSllAmtSmtl()),
                    parseBd(response.output2().dmstFeeSmtl()),
                    parseBd(response.output2().ovrsFeeSmtl())
            );
        }
        return new DailyTransactionResult(items, summary);
    }

    // sll_buy_dvsn_cd: 01=매도, 02=매수
    private static Order.OrderDirection parseDirection(String code) {
        return "01".equals(code) ? Order.OrderDirection.SELL : Order.OrderDirection.BUY;
    }

    private static int parseIntSafe(String s) {
        try { return s == null || s.isBlank() ? 0 : (int) Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static BigDecimal parseBd(String s) {
        try { return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static DailyTransactionSummary emptySummary() {
        return new DailyTransactionSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    record TransactionResponse(
            @JsonProperty("output1") List<Output1> output1,
            @JsonProperty("output2") Output2 output2
    ) {
        record Output1(
                @JsonProperty("trad_dt") String tradDt,                    // 매매일자
                @JsonProperty("sttl_dt") String sttlDt,                    // 결제일자
                @JsonProperty("sll_buy_dvsn_cd") String sllBuyDvsnCd,      // 매도매수구분코드 (01=매도, 02=매수)
                @JsonProperty("pdno") String pdno,                          // 종목코드
                @JsonProperty("ovrs_item_name") String ovrsItemName,        // 종목명
                @JsonProperty("ccld_qty") String ccldQty,                   // 체결수량
                @JsonProperty("ovrs_stck_ccld_unpr") String ovrsStckCcldUnpr, // 해외주식체결단가
                @JsonProperty("tr_frcr_amt2") String trFrcrAmt2,           // 거래외화금액
                @JsonProperty("wcrc_excc_amt") String wcrcExccAmt,         // 원화정산금액
                @JsonProperty("erlm_exrt") String erlmExrt,                 // 등록환율
                @JsonProperty("crcy_cd") String crcyCd                      // 통화코드
        ) {}

        record Output2(
                @JsonProperty("frcr_buy_amt_smtl") String frcrBuyAmtSmtl, // 외화매수금액합계
                @JsonProperty("frcr_sll_amt_smtl") String frcrSllAmtSmtl, // 외화매도금액합계
                @JsonProperty("dmst_fee_smtl") String dmstFeeSmtl,        // 국내수수료합계
                @JsonProperty("ovrs_fee_smtl") String ovrsFeeSmtl         // 해외수수료합계
        ) {}
    }
}
