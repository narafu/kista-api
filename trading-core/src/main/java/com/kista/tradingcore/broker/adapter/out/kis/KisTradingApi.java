package com.kista.tradingcore.broker.adapter.out.kis;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.time.UsTradeDates;
import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.broker.domain.model.*;
import com.kista.tradingcore.broker.domain.model.kis.KisApiException;
import com.kista.shared.kernel.StrategyTicker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
class KisTradingApi {

    private static final String BALANCE_PATH  = "/uapi/overseas-stock/v1/trading/inquire-balance";
    private static final String BALANCE_TR_ID = "TTTS3012R"; // 해외주식 잔고 조회

    static final String MARGIN_PATH = "/uapi/overseas-stock/v1/trading/foreign-margin"; // KisAuthApi.verifyAccount 공용
    static final String MARGIN_TR_ID = "TTTC2101R"; // 해외증거금 통화별조회 — KisAuthApi.verifyAccount 공용
    private static final String TARGET_NATION = "미국";

    private static final String PORTFOLIO_PATH = "/uapi/overseas-stock/v1/trading/inquire-present-balance";
    private static final String PORTFOLIO_TR_ID = "CTRP6504R"; // 해외주식 체결기준현재잔고 (실전투자)

    private static final String EXECUTION_PATH  = "/uapi/overseas-stock/v1/trading/inquire-ccnl";
    private static final String EXECUTION_TR_ID = "TTTS3035R"; // 해외주식 체결 내역 조회

    private static final int MAX_PAGES = 50; // 연속조회 상한 — 초과 시 warn 후 누적분 반환(Meritz와 동일 정책)

    private static final DateTimeFormatter FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisHttpClient kisHttpClient;
    private final KisExchangeRegistry exchangeRegistry;

    private HoldingResult fetchHolding(BrokerAccountRef account, StrategyTicker ticker) {
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

    public List<MarginItem> getMargin(BrokerAccountRef account) {
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

    public BigDecimal getUsdBuyableAmount(BrokerAccountRef account) {
        // getMargin()은 MarginPort 구현 — getBalance()에서도 사용
        return getMargin(account).stream()
                .filter(item -> Currency.USD == item.currency())
                .findFirst()
                .map(MarginItem::purchasableAmount)
                .orElse(BigDecimal.ZERO);
    }

    // ── PortfolioPort.getPresentBalance() ─────────────────────────────────────

    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
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

    // TTTS3012R 잔고수량 조회 (CTRP6504R cblc_qty13은 실잔고보다 적게 반환되는 사례 있음)
    public SellableQuantity getSellableQuantity(StrategyTicker ticker, BrokerAccountRef account) {
        int quantity = fetchHolding(account, ticker).quantity();
        log.info("KIS 판매 가능 수량: ticker={}, quantity={}", ticker, quantity);
        return new SellableQuantity(ticker.name(), quantity);
    }

    // ── KisExecutionPort ───────────────────────────────────────────────────────

    public List<Execution> getExecutions(LocalDate from, LocalDate to, StrategyTicker ticker, BrokerAccountRef account) {
        List<ExecutionListResponse.OutputItem> items = fetchOrderList(from, to, ticker, account);
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        LocalDate usFrom = UsTradeDates.toUsTradeDate(from); // KST fallback → US 거래일 기준으로 역변환 대응
        return items.stream()
                .filter(item -> KisResponseParser.parseIntSafe(item.filledQuantity()) > 0) // 미체결(ft_ccld_qty=0) 제외
                .map(item -> new Execution(
                        // KIS ord_dt는 US 거래일 → toKstTradeDate()로 도메인 KST 일자로 복원
                        UsTradeDates.toKstTradeDate(KisResponseParser.parseDate(item.ordDt(), usFrom)),
                        ticker,
                        KisResponseParser.parseDirection(item.sllBuyDvsnCd()),
                        KisResponseParser.parseIntSafe(item.filledQuantity()),
                        KisResponseParser.parseBd(item.ftCcldUnpr3()),
                        KisResponseParser.parseBd(item.ccldAmt()),
                        item.odno() // KIS ODNO → externalOrderId
                ))
                .toList();
    }

    // 취소 전 원주문 상태 조회 — KIS는 ODNO 필터를 지원하지 않아 종목·기간 목록에서 주문번호로 찾는다(없으면 empty)
    // 검색 범위일 뿐 거래일 변환이 아니다 — 취소 대상은 오늘·다음 거래일 주문이라 KST 오늘 앞뒤 1일이면 덮는다
    Optional<OrderStatusView> findOrder(StrategyTicker ticker, String odno, BrokerAccountRef account) {
        LocalDate today = LocalDate.now(TimeZones.KST);
        return fetchOrderList(today.minusDays(1), today.plusDays(1), ticker, account).stream()
                .filter(item -> odno.equals(item.odno()))
                .findFirst()
                .map(item -> new OrderStatusView(
                        KisResponseParser.parseIntSafe(item.orderedQuantity()),
                        KisResponseParser.parseIntSafe(item.filledQuantity()),
                        KisResponseParser.parseIntSafe(item.unfilledQuantity())));
    }

    // 해외주식 주문체결내역(TTTS3035R) — 연속조회로 전 페이지 누적 후 전략 종목 행만 반환, 응답 없음은 빈 목록
    // 연속조회 규약(KIS 공식 샘플 기준, 실계좌 미실측): 응답 헤더 tr_cont F/M이면 다음 페이지 — 요청 헤더 tr_cont=N + 응답 ctx_area_fk200/nk200 원문 재전송
    private List<ExecutionListResponse.OutputItem> fetchOrderList(LocalDate from, LocalDate to, StrategyTicker ticker,
                                                                  BrokerAccountRef account) {
        List<ExecutionListResponse.OutputItem> all = new ArrayList<>();
        String trCont = null;     // 첫 페이지는 tr_cont 헤더 없음
        String fk200 = "";
        String nk200 = "";
        for (int page = 1; ; page++) {
            String pageFk = fk200;
            String pageNk = nk200;
            KisHttpClient.Page<ExecutionListResponse> res = kisHttpClient.tradingGetPage(
                    EXECUTION_TR_ID, EXECUTION_PATH, account, ExecutionListResponse.class, trCont,
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
                        p.add("CTX_AREA_NK200", pageNk);          // 연속조회 키(첫 페이지는 빈 값)
                        p.add("CTX_AREA_FK200", pageFk);
                    });
            ExecutionListResponse body = res == null ? null : res.body();
            if (body != null && body.output() != null) {
                all.addAll(body.output());
            }
            if (res == null || !res.hasNext()) {
                break;
            }
            if (page >= MAX_PAGES) {
                log.warn("KIS 주문체결내역 연속조회 {}페이지 상한 도달 — 누적 {}건만 반환: accountId={}", MAX_PAGES, all.size(), account.id());
                break;
            }
            trCont = "N"; // 연속조회 다음 페이지
            fk200 = body == null || body.ctxAreaFk200() == null ? "" : body.ctxAreaFk200();
            nk200 = body == null || body.ctxAreaNk200() == null ? "" : body.ctxAreaNk200();
            // 키가 그대로면 같은 페이지를 다시 받아 체결이 중복 집계된다 — 이상 응답으로 보고 중단
            if (fk200.equals(pageFk) && nk200.equals(pageNk)) {
                log.warn("KIS 주문체결내역 연속조회 키 미변경 — {}페이지에서 중단, 누적 {}건 반환: accountId={}", page, all.size(), account.id());
                break;
            }
        }
        return all.stream().filter(item -> ticker.name().equals(item.pdno())).toList();
    }

    // 원주문 수량 현황 — 주문·체결·미체결(잔량) 수량
    record OrderStatusView(int ordered, int filled, int unfilled) {}

    // KST 날짜 → KIS API 날짜 파라미터 (US 거래일 YYYYMMDD)
    private String formatTradeDate(LocalDate kst) {
        return UsTradeDates.toUsTradeDate(kst).format(FMT);
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

    record ExecutionListResponse(
            @JsonProperty("output") List<OutputItem> output,
            @JsonProperty("ctx_area_fk200") String ctxAreaFk200, // 연속조회 키 FK
            @JsonProperty("ctx_area_nk200") String ctxAreaNk200  // 연속조회 키 NK
    ) {
        record OutputItem(
                @JsonProperty("pdno") String pdno,                      // 종목코드
                @JsonProperty("ord_dt") String ordDt,                   // 주문일자 (YYYYMMDD)
                @JsonProperty("sll_buy_dvsn_cd") String sllBuyDvsnCd,  // 매도매수구분: 01=매도, 02=매수
                @JsonProperty("ft_ord_qty") String orderedQuantity,     // FT주문수량
                @JsonProperty("ft_ccld_qty") String filledQuantity,     // FT체결수량
                @JsonProperty("nccs_qty") String unfilledQuantity,      // 미체결수량
                @JsonProperty("ft_ccld_unpr3") String ftCcldUnpr3,      // FT체결단가
                @JsonProperty("ft_ccld_amt3") String ccldAmt,           // FT체결금액
                @JsonProperty("odno") String odno                       // 주문번호
        ) {}
    }

}
