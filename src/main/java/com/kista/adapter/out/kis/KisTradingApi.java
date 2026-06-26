package com.kista.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.common.TradeDateConverter;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.kis.*;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.kista.domain.model.order.Order;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisTradingApi implements KisAccountPort,
        KisExecutionPort, KisDailyTransactionPort,
        KisPortfolioPort, KisMarginPort, KisSellableQuantityPort {

    private static final String BALANCE_PATH  = "/uapi/overseas-stock/v1/trading/inquire-balance";
    private static final String BALANCE_TR_ID = "TTTS3012R"; // 해외주식 잔고 조회

    static final String MARGIN_PATH = "/uapi/overseas-stock/v1/trading/foreign-margin"; // KisAuthApi.testAccountNo 공용
    static final String MARGIN_TR_ID = "TTTC2101R"; // 해외증거금 통화별조회 — KisAuthApi.testAccountNo 공용
    private static final String TARGET_NATION = "미국";

    private static final String PORTFOLIO_PATH = "/uapi/overseas-stock/v1/trading/inquire-present-balance";
    private static final String PORTFOLIO_TR_ID = "CTRP6504R"; // 해외주식 체결기준현재잔고 (실전투자)

    private static final String EXECUTION_PATH  = "/uapi/overseas-stock/v1/trading/inquire-ccnl";
    private static final String EXECUTION_TR_ID = "TTTS3035R"; // 해외주식 체결 내역 조회

    private static final String DAILY_TRANS_PATH = "/uapi/overseas-stock/v1/trading/inquire-period-trans";
    private static final String DAILY_TRANS_TR_ID = "CTOS4001R"; // 해외주식 일별거래내역

    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisHttpClient kisHttpClient;
    private final KisExchangeRegistry exchangeRegistry;

    // ── KisAccountPort ─────────────────────────────────────────────────────────

    @Override
    public AccountBalance getBalance(Account account, Ticker ticker) {
        HoldingResult holding = fetchHolding(account, ticker);
        BigDecimal usdDeposit = fetchUsdDeposit(account);
        BigDecimal avgPrice = holding.quantity() > 0 ? holding.avgPrice() : null;
        return new AccountBalance(holding.quantity(), avgPrice, usdDeposit);
    }

    private HoldingResult fetchHolding(Account account, Ticker ticker) {
        BalanceResponse response = kisHttpClient.tradingGet(
                BALANCE_TR_ID, BALANCE_PATH, account, BalanceResponse.class,
                p -> {
                    p.add("OVRS_EXCG_CD", exchangeRegistry.defaultUsExchange()); // 실전 미국전체
                    p.add("TR_CRCY_CD", "USD");
                    p.add("CTX_AREA_FK200", "");
                    p.add("CTX_AREA_NK200", "");
                });
        if (response == null || response.output1() == null) {
            return new HoldingResult(0, BigDecimal.ZERO);
        }
        return response.output1().stream()
                .filter(o -> ticker.name().equals(o.pdno()))
                .findFirst()
                .map(o -> new HoldingResult(
                        KisResponseParser.parseIntSafe(o.balanceQuantity()),
                        KisResponseParser.parseBd(o.pchsAvgPric())))
                .orElse(new HoldingResult(0, BigDecimal.ZERO));
    }

    // ── MarginPort.getMargin() ────────────────────────────────────────────────

    @Override
    public List<MarginItem> getMargin(Account account) {
        MarginResponse response = kisHttpClient.tradingGet(
                MARGIN_TR_ID, MARGIN_PATH, account, MarginResponse.class, p -> {});
        if (response == null || response.output() == null) {
            throw new KisApiException("증거금 조회 응답 없음", null);
        }
        // natn_name == "미국" 행만 필터링 — crcy_cd 기준 시 동일 itgr_ord_psbl_amt 중복 행 발생
        // purchasableAmount = max(itgr, gnrl): 통합증거금 ON이면 itgr>gnrl, OFF이면 itgr=0&gnrl=잔액
        return response.output().stream()
                .filter(o -> TARGET_NATION.equals(o.natnName()))
                .flatMap(o -> Currency.tryParse(o.crcyCd())
                        .map(c -> {
                            BigDecimal integrated = KisResponseParser.parseBd(o.itgrOrdPsblAmt());
                            BigDecimal gnrl = KisResponseParser.parseBd(o.frcrGnrlOrdPsblAmt());
                            BigDecimal purchasable = integrated.max(gnrl);
                            return new MarginItem(c, integrated, gnrl, purchasable,
                                    KisResponseParser.parseBd(o.usdToKrwRate()));
                        })
                        .stream())
                .toList();
    }

    // ── MarginPort.getUsdBuyableAmount() ──────────────────────────────────────

    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        // getMargin()은 MarginPort 구현 — KisAccountPort.getBalance()에서도 사용
        return getMargin(account).stream()
                .filter(item -> Currency.USD == item.currency())
                .findFirst()
                .map(MarginItem::purchasableAmount)
                .orElse(BigDecimal.ZERO);
    }

    // 내부 헬퍼: USD 주문가능금액 추출 (KisAccountPort.getBalance에서 사용)
    private BigDecimal fetchUsdDeposit(Account account) {
        return getUsdBuyableAmount(account);
    }

    // ── PortfolioPort.getPresentBalance() ─────────────────────────────────────

    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
        PortfolioResponse response = kisHttpClient.tradingGet(
                PORTFOLIO_TR_ID, PORTFOLIO_PATH, account, PortfolioResponse.class,
                p -> {
                    p.add("WCRC_FRCR_DVSN_CD", "02"); // 02=외화
                    p.add("NATN_CD", "000");           // 000=전체
                    p.add("TR_MKET_CD", "00");         // 00=전체
                    p.add("INQR_DVSN_CD", "00");       // 00=전체
                });
        if (response == null) {
            return new PresentBalanceResult(Collections.emptyList(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        List<PresentBalanceResult.Item> items = KisResponseParser.streamTickered(response.output1(),
                PortfolioResponse.Output1::pdno,
                (ticker, o) -> new PresentBalanceResult.Item(
                        ticker,
                        KisResponseParser.parseIntSafe(o.balanceQuantity13()),
                        KisResponseParser.parseBd(o.avgUnpr3()),
                        KisResponseParser.parseBd(o.ovrsNowPric1()),
                        KisResponseParser.parseBd(o.frcrEvluAmt2()),
                        KisResponseParser.parseBd(o.evluPflsAmt2()),
                        KisResponseParser.parseBd(o.evluPflsRt1()),
                        o.ovrsExcgCd()
                ));
        BigDecimal totalAsset = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal totalRate = BigDecimal.ZERO;
        if (response.output3() != null) {
            totalAsset = KisResponseParser.parseBd(response.output3().totAsstAmt());
            totalProfit = KisResponseParser.parseBd(response.output3().totEvluPflsAmt());
            totalRate = KisResponseParser.parseBd(response.output3().evluErngRt1());
        }
        return new PresentBalanceResult(items, totalAsset, totalProfit, totalRate, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    // ── SellableQuantityPort.getSellableQuantity() ────────────────────────────

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return fetchSellableQuantity(ticker, account);
    }

    // 내부 헬퍼: TTTS3012R 잔고수량 조회 (CTRP6504R cblc_qty13은 실잔고보다 적게 반환되는 사례 있음)
    private SellableQuantity fetchSellableQuantity(Ticker ticker, Account account) {
        int quantity = fetchHolding(account, ticker).quantity();
        log.info("KIS 판매 가능 수량: ticker={}, quantity={}", ticker, quantity);
        return new SellableQuantity(ticker.name(), quantity);
    }

    // ── KisExecutionPort ───────────────────────────────────────────────────────

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        ExecutionListResponse response = kisHttpClient.tradingGet(
                EXECUTION_TR_ID, EXECUTION_PATH, account, ExecutionListResponse.class,
                p -> {
                    p.add("PDNO", ticker.name());              // 전략 종목만 조회
                    p.add("ORD_STRT_DT", formatTradeDate(from)); // KST → UTC(US거래일)
                    p.add("ORD_END_DT", formatTradeDate(to));
                    p.add("SLL_BUY_DVSN", "00");              // 전체
                    p.add("CCLD_NCCS_DVSN", "00");            // 전체
                    p.add("OVRS_EXCG_CD", exchangeRegistry.ovrsExcgCd(ticker)); // 전략 종목 거래소
                    p.add("SORT_SQN", "DS");                  // 정순
                    p.add("ORD_DT", "");
                    p.add("ORD_GNO_BRNO", "");
                    p.add("ODNO", "");
                    p.add("CTX_AREA_NK200", "");
                    p.add("CTX_AREA_FK200", "");
                });
        if (response == null || response.output() == null) {
            return Collections.emptyList();
        }
        LocalDate utcFrom = TradeDateConverter.toUtc(from); // KST fallback → UTC 기준으로 역변환 대응
        return response.output().stream()
                .filter(item -> ticker.name().equals(item.pdno()))
                .map(item -> new Execution(
                        // KIS ord_dt는 UTC(US거래일) → toKst()로 도메인 KST 일자로 복원
                        TradeDateConverter.toKst(KisResponseParser.parseDate(item.ordDt(), utcFrom)),
                        ticker,
                        KisResponseParser.parseDirection(item.sllBuyDvsnCd()),
                        KisResponseParser.parseIntSafe(item.filledQuantity()),
                        KisResponseParser.parseBd(item.ftCcldUnpr3()),
                        KisResponseParser.parseBd(item.ccldAmt()),
                        item.odno() // KIS ODNO → externalOrderId
                ))
                .toList();
    }

    // ── KisDailyTransactionPort ────────────────────────────────────────────────

    @Override
    public DailyTransactionResult getDailyTransactions(LocalDate from, LocalDate to, Account account) {
        TransactionResponse response = kisHttpClient.tradingGet(
                DAILY_TRANS_TR_ID, DAILY_TRANS_PATH, account, TransactionResponse.class,
                p -> {
                    p.add("ERLM_STRT_DT", formatTradeDate(from)); // KST → UTC(US거래일)
                    p.add("ERLM_END_DT", formatTradeDate(to));
                    p.add("OVRS_EXCG_CD", exchangeRegistry.defaultUsExchange()); // 미국 전체
                    p.add("PDNO", "");                         // 전종목
                    p.add("SLL_BUY_DVSN_CD", "00");           // 00=전체, 01=매도, 02=매수
                    p.add("LOAN_DVSN_CD", "");
                    p.add("CTX_AREA_FK100", "");
                    p.add("CTX_AREA_NK100", "");
                });
        if (response == null) {
            return new DailyTransactionResult(Collections.emptyList(), emptySummary());
        }
        List<DailyTransaction> items = KisResponseParser.streamTickered(response.output1(),
                TransactionResponse.Output1::pdno,
                (ticker, o) -> {
                    int qty = KisResponseParser.parseIntSafe(o.filledQuantity());
                    BigDecimal tradeAmountUsd = KisResponseParser.parseBd(o.trFrcrAmt2());
                    // ovrs_stck_ccld_unpr은 KRW 단가를 반환하므로 USD 단가를 직접 계산
                    BigDecimal priceUsd = qty > 0
                            ? tradeAmountUsd.divide(BigDecimal.valueOf(qty), 6, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return new DailyTransaction(
                            LocalDate.parse(o.tradDt(), FMT).toString(), // YYYYMMDD → ISO YYYY-MM-DD
                            o.sttlDt(),
                            KisResponseParser.parseDirection(o.sllBuyDvsnCd()),
                            ticker,
                            o.ovrsItemName(),
                            qty,
                            priceUsd,
                            tradeAmountUsd,
                            KisResponseParser.parseBd(o.wcrcExccAmt()),
                            KisResponseParser.parseBd(o.erlmExrt()),
                            o.crcyCd()
                    );
                });
        // KIS summary의 frcr_buy/sll_amt_smtl은 KRW 단위이므로 items에서 USD로 재계산 (Toss와 동일 방식)
        BigDecimal buyTotal = items.stream()
                .filter(t -> t.direction() == Order.OrderDirection.BUY)
                .map(DailyTransaction::tradeAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sellTotal = items.stream()
                .filter(t -> t.direction() == Order.OrderDirection.SELL)
                .map(DailyTransaction::tradeAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        DailyTransactionSummary summary = response.output2() != null
                ? new DailyTransactionSummary(buyTotal, sellTotal,
                        KisResponseParser.parseBd(response.output2().dmstFeeSmtl()),
                        KisResponseParser.parseBd(response.output2().ovrsFeeSmtl()))
                : new DailyTransactionSummary(buyTotal, sellTotal, BigDecimal.ZERO, BigDecimal.ZERO);
        return new DailyTransactionResult(items, summary);
    }

    // KST 날짜 → KIS API 날짜 파라미터 (UTC=US거래일 YYYYMMDD)
    private String formatTradeDate(LocalDate kst) {
        return TradeDateConverter.toUtc(kst).format(FMT);
    }

    private static DailyTransactionSummary emptySummary() {
        return new DailyTransactionSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    // ── Inner response records ─────────────────────────────────────────────────

    record HoldingResult(int quantity, BigDecimal avgPrice) {}

    record BalanceResponse(@JsonProperty("output1") List<Output1> output1) {
        record Output1(
                @JsonProperty("ovrs_pdno") String pdno,                   // 해외상품번호
                @JsonProperty("ovrs_cblc_qty") String balanceQuantity,    // 해외잔고수량
                @JsonProperty("pchs_avg_pric") String pchsAvgPric         // 매입평균가격
        ) {}
    }

    record MarginResponse(@JsonProperty("output") List<Output> output) {
        record Output(
                @JsonProperty("natn_name") String natnName,                        // 국가명 (미국, 일본 등)
                @JsonProperty("crcy_cd") String crcyCd,                            // 통화코드 (USD 등)
                @JsonProperty("itgr_ord_psbl_amt") String itgrOrdPsblAmt,          // 통합주문가능금액
                @JsonProperty("frcr_gnrl_ord_psbl_amt") String frcrGnrlOrdPsblAmt, // 외화일반주문가능금액
                @JsonProperty("bass_exrt") String usdToKrwRate                     // 기준환율
        ) {}
    }

    record PortfolioResponse(
            @JsonProperty("output1") List<Output1> output1,
            @JsonProperty("output3") Output3 output3
    ) {
        record Output1(
                @JsonProperty("pdno") String pdno,                    // 종목코드
                @JsonProperty("cblc_qty13") String balanceQuantity13, // 잔고수량
                @JsonProperty("avg_unpr3") String avgUnpr3,           // 평균단가
                @JsonProperty("ovrs_now_pric1") String ovrsNowPric1,  // 현재가
                @JsonProperty("frcr_evlu_amt2") String frcrEvluAmt2,  // 외화평가금액
                @JsonProperty("evlu_pfls_amt2") String evluPflsAmt2,  // 평가손익
                @JsonProperty("evlu_pfls_rt1") String evluPflsRt1,   // 평가손익율
                @JsonProperty("ovrs_excg_cd") String ovrsExcgCd      // 해외거래소코드
        ) {}

        record Output3(
                @JsonProperty("tot_asst_amt") String totAsstAmt,          // 총자산금액
                @JsonProperty("tot_evlu_pfls_amt") String totEvluPflsAmt, // 총평가손익
                @JsonProperty("evlu_erng_rt1") String evluErngRt1         // 총수익률
        ) {}
    }

    record ExecutionListResponse(@JsonProperty("output") List<OutputItem> output) {
        record OutputItem(
                @JsonProperty("pdno") String pdno,                      // 종목코드
                @JsonProperty("ord_dt") String ordDt,                   // 주문일자 (YYYYMMDD)
                @JsonProperty("sll_buy_dvsn_cd") String sllBuyDvsnCd,  // 매도매수구분: 01=매도, 02=매수
                @JsonProperty("ft_ccld_qty") String filledQuantity,     // FT체결수량
                @JsonProperty("ft_ccld_unpr3") String ftCcldUnpr3,      // FT체결단가
                @JsonProperty("ft_ccld_amt3") String ccldAmt,           // FT체결금액
                @JsonProperty("odno") String odno                       // 주문번호
        ) {}
    }

    record TransactionResponse(
            @JsonProperty("output1") List<Output1> output1,
            @JsonProperty("output2") Output2 output2
    ) {
        record Output1(
                @JsonProperty("trad_dt") String tradDt,                      // 매매일자
                @JsonProperty("sttl_dt") String sttlDt,                      // 결제일자
                @JsonProperty("sll_buy_dvsn_cd") String sllBuyDvsnCd,        // 매도매수구분코드
                @JsonProperty("pdno") String pdno,                            // 종목코드
                @JsonProperty("ovrs_item_name") String ovrsItemName,          // 종목명
                @JsonProperty("ccld_qty") String filledQuantity,               // 체결수량
                @JsonProperty("ovrs_stck_ccld_unpr") String ovrsStckCcldUnpr, // 체결단가
                @JsonProperty("tr_frcr_amt2") String trFrcrAmt2,              // 거래외화금액
                @JsonProperty("wcrc_excc_amt") String wcrcExccAmt,            // 원화정산금액
                @JsonProperty("erlm_exrt") String erlmExrt,                   // 등록환율
                @JsonProperty("crcy_cd") String crcyCd                        // 통화코드
        ) {}

        record Output2(
                @JsonProperty("frcr_buy_amt_smtl") String frcrBuyAmtSmtl, // 외화매수금액합계
                @JsonProperty("frcr_sll_amt_smtl") String frcrSllAmtSmtl, // 외화매도금액합계
                @JsonProperty("dmst_fee_smtl") String dmstFeeSmtl,        // 국내수수료합계
                @JsonProperty("ovrs_fee_smtl") String ovrsFeeSmtl         // 해외수수료합계
        ) {}
    }
}
