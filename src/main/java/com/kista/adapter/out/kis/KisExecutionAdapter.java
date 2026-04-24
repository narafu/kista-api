package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.Execution;
import com.kista.domain.model.Order;
import com.kista.domain.port.out.KisExecutionPort;
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
public class KisExecutionAdapter implements KisExecutionPort {

    private static final String PATH  = "/uapi/overseas-stock/v1/trading/inquire-ccnl";
    private static final String TR_ID = "TTTS3035R";
    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisHttpClient kisHttpClient;

    public KisExecutionAdapter(KisHttpClient kisHttpClient) {
        this.kisHttpClient = kisHttpClient;
    }

    @Override
    public List<Execution> getExecutions(String token, LocalDate date) {
        HttpHeaders headers = kisHttpClient.buildHeaders(token, TR_ID);
        String dateStr = date.format(FMT);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("CANO", kisHttpClient.props().accountNo());
        params.add("ACNT_PRDT_CD", kisHttpClient.props().accountType());
        params.add("INQR_STRT_DT", dateStr);
        params.add("INQR_END_DT", dateStr);
        params.add("CTX_AREA_FK200", "");
        params.add("CTX_AREA_NK200", "");

        ExecutionListResponse response = kisHttpClient.get(PATH, headers, params, ExecutionListResponse.class);

        if (response == null || response.output() == null) {
            return Collections.emptyList();
        }
        return response.output().stream()
                .map(item -> new Execution(
                        date,
                        item.pdno(),
                        resolveDirection(item.selnByovCls()),
                        parseIntSafe(item.ftCcldQty()),
                        parseBigDecimalSafe(item.ftCcldUnpr3()),
                        parseBigDecimalSafe(item.ccldAmt()),
                        item.odno()
                ))
                .toList();
    }

    private Order.OrderDirection resolveDirection(String selnByovCls) {
        return "01".equals(selnByovCls) ? Order.OrderDirection.SELL : Order.OrderDirection.BUY;
    }

    private static int parseIntSafe(String s) {
        try { return s == null || s.isBlank() ? 0 : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static BigDecimal parseBigDecimalSafe(String s) {
        try { return s == null || s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s.trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    record ExecutionListResponse(@JsonProperty("output") List<OutputItem> output) {
        record OutputItem(
                @JsonProperty("PDNO") String pdno,
                @JsonProperty("SELN_BYOV_CLS") String selnByovCls,
                @JsonProperty("FT_CCLD_QTY") String ftCcldQty,
                @JsonProperty("FT_CCLD_UNPR3") String ftCcldUnpr3,
                @JsonProperty("CCLD_AMT") String ccldAmt,
                @JsonProperty("ODNO") String odno
        ) {}
    }
}
