## KIS API

- 모든 KIS 호출은 `KisHttpClient` 경유 (공통 헤더: `authorization`, `appkey`, `appsecret`, `tr_id`, `custtype: P`)
- `KisHttpClient.buildHeaders(String trId, Account account)` — Account 계좌별 자격증명으로 헤더 구성
- `KisTokenPort.getToken(UUID accountId, String appKey, String appSecret)` — account_id 기반 독립 토큰 캐시 (`kis_tokens` PK = account_id UUID)
- 토큰 관리는 `KisTokenAdapter`만 담당; `findValidToken(accountId, now.plusMinutes(1))` — 만료 1분 전부터 재발급 (경계값 EGW00123 방지)
- **`KisTokenAdapter`는 `KisHttpClient` 미사용** — `RestTemplate`+`KisProperties` 직접 주입 (`KisHttpClient`→`KisTokenPort`→`KisTokenAdapter` 순환 방지)
- **토큰 발급 우회 경로 주의** — `/oauth2/tokenP`를 직접 호출하는 두 곳: `KisConnectionTestAdapter.test()` / `KisTokenAdapter.testToken()`. 두 곳 모두 `accountId` 파라미터 필수, 성공 시 `kisTokenCachePort.saveToken()` 저장 (누락 시 KIS에서 "접속요청 발행" 알림 매번 발생)
- `testToken(accountId, appKey, appSecret)` — `AccountService.update()` 호출, 발급 토큰을 캐시 저장 → 직후 API 호출 재발급 방지
- `KisConnectionTestPort.test(appKey, appSecret, UUID accountId)` — accountId=null이면 캐시 저장 생략 (등록 전 사전 검증), accountId 있으면 저장
- `KisTokenAdapter.getToken()` — accountId별 `ReentrantLock` + double-check locking으로 동시 요청 경합 차단 (대시보드 동시 호출 시 N번 발급 방지)
- 모든 KIS 포트 인터페이스에 `Account account` 파라미터 추가 (V2) — `getBalance(Account)`, `place(Order, Account)`, `isMarketOpen(LocalDate, Account)` 등
- KIS 포트 인터페이스에 `token` 파라미터 없음 — 서비스 레이어에서 token 관리·전달 금지
- Base URL: `https://openapi.koreainvestment.com:9443`

### KIS 주요 오류 코드
- `EGW00202` "GW라우팅 중 오류가 발생했습니다" — 세 가지 원인:
  1. **미국 공휴일에 주문 접수**: `KisHolidayAdapter` 404 폴백 → 휴장일에 주문 시도 → KIS 거부
  2. **LOC 주문에 가격 "0" 전송**: LOC(장마감지정가)는 실제 limit price 필수. `"0"` 전송 시 "$0 이하 체결" 불가 조건으로 판단해 거부 (과거 `formatPrice(LOC,price)="0"` 버그, 수정 완료)
  3. **주문 body를 Map(compact JSON)으로 전송**: KIS GW는 raw JSON String 포맷만 허용 — `KisOrderAdapter.place()`는 `String.format()` 방식 사용 (LinkedHashMap → Jackson 직렬화 금지)
  - 재시도 로직 없음 — 원인 제거로만 해결
- `EGW00123` — 토큰 만료 경계값 오류 (만료 1분 전 재발급으로 방지 중, `KisTokenAdapter`)
- `APBK0988` "주문수량이 가능수량보다 큽니다" — 매도 주문 수량 > KIS 실잔고(DB 이력과 불일치) 또는 매수 금액 > 실가용자금. 주문 계산 후 2-조건 체크(`totalBuyAmount > usdDeposit OR totalSellQuantity > holdings`)로 사전 감지 가능

### Alpaca Calendar API (`/v2/calendar`, AlpacaCalendarAdapter)
- 지원 범위: 1970~2029년 — 2026년 기준 최대 3년 선제 적재 가능
- 스케줄: 1월 1일 00:00 KST 3년치(`year`~`year+2`) 적재 / 매월 1일 01:00 KST 당월 최신화 (`MarketCalendarRefreshScheduler`)
- `refreshCalendar(year)`: 연간 전체 교체(`replaceByYear`) / `refreshMonth(year, month)`: 월별 교체(`replaceByMonth`)

### KIS 휴장 조회 API (`CTOS5011R`, KisHolidayAdapter)
- `BASS_DT` = **한국 날짜 기준** — 미국 메모리얼 데이(ET 5/25 월) = 한국 5/26에 조회해야 정확 (JVM TZ=KST 고정으로 해결됨)
- **`NATN_CD=840` 필수** (미국 국가코드) — 누락 시 KIS 404 반환 → 폴백으로 공휴일 미감지
- `output[]` 비어있으면 거래일, 있으면 휴장일
- API 호출 실패 시 "개장으로 폴백" (`catch → return true`) — KIS 일시 장애 시 매매 진행 위험 있음

### 응답 필드명 대소문자 주의
- **해외주식 API 응답은 lowercase** (`ovrs_pdno`, `ovrs_cblc_qty`, `frcr_evlu_amt2` 등)
- 국내주식 API는 UPPERCASE — 혼동 주의, `@JsonProperty` 값 반드시 소문자로

### 노출된 KIS live REST 엔드포인트 (신규 추가 전 확인)
- `GET /api/accounts/{accountId}/prices?tickers=TQQQ,SOXL,USD` — `KisStatisticsController:240`, `Map<Ticker,BigDecimal>` 응답, `accounts/[[...path]]` catch-all로 kista-ui 프록시됨
- `GET /api/accounts/{accountId}/margin` — `KisStatisticsController:155`, `List<MarginItem>` 응답, USD 예수금은 `currency=="USD"` 행의 `integratedOrderableAmount`
- `GET /api/privacy-trades/base/latest` — `PrivacyTradeController`, trade_date>=오늘 중 가장 미래 SOXL 기준가(`currentCycleStart`), 없으면 404
- `GET /api/market/holidays?year=YYYY&month=MM` — `MarketHolidayController`, 해당 월 미국 시장 휴장일 날짜 목록(`List<String>`, ISO 형식), JWT 인증 필요

### 잔고 조회 API 파라미터
- `TTTS3012R` (해외주식 잔고): `CANO`, `ACNT_PRDT_CD`, `OVRS_EXCG_CD=NASD`(실전 미국전체), `TR_CRCY_CD=USD`, `CTX_AREA_FK200=""`, `CTX_AREA_NK200=""`
- `TTTC2101R` (해외증거금 통화별조회): `CANO`, `ACNT_PRDT_CD` — 응답 `output` 목록에서 `natn_name=="미국"` 행의 `itgr_ord_psbl_amt`를 `usdDeposit`으로 사용
  - `frcr_dncl_amt_2`(환전된 외화만) 대신 `itgr_ord_psbl_amt`(통합주문가능금액) 사용 — 원화 자동 환전 포함
  - **필터 기준: `natn_name == "미국"` 단일 행** — `crcy_cd == "USD"`로 필터하면 동일 `itgr_ord_psbl_amt` 값을 가진 중복 행이 여러 개 반환됨
  - `MarginItem` 도메인 모델: `currency`, `integratedOrderableAmount` 2개 필드만 — `frcr_dncl_amt_2`(foreignBalance) 불필요, 제거됨
- API 파라미터 불확실 시 `kis-coding-mcp`의 `search_overseas_stock_api` + `read_source_code`로 공식 확인

### 주문 API (KisOrderAdapter)
- 미국 매수 TR ID: `TTTT1002U`, 미국 매도: `TTTT1006U` (일본은 TTTS0308U/0307U — 혼동 주의)
- `ORD_DVSN` 코드: LOC(장마감지정가)=`34`, MOC(장마감시장가)=`33`, LOO(장개시지정가)=`32`, 지정가=`00`
- MOC(장마감시장가) 주문 시 `OVRS_ORD_UNPR="0"`, **LOC(장마감지정가)는 실제 limit price 필수**
- **KIS 가격 파라미터 포맷팅 SSOT**: `KisResponseParser.formatPrice(type, price)` — MOC(시장가)만 `"0"`, LOC/LIMIT(지정가)는 `setScale(2, HALF_UP).toPlainString()`. `price.toPlainString()` 직접 사용 금지 (scale=4 값 전송 시 KIS 오류)
- LOC(장마감지정가)에 `"0"` 전송 금지 — KIS가 $0 이하 체결 불가 주문으로 판단해 EGW00202 반환. KIS API 스펙: `"0"`은 시장가(MOC/시장가)에만 허용
- **주문 body는 반드시 raw JSON String**: `String.format("""...""", ...)` 방식으로 직접 구성 — `Map<String, String>` + RestTemplate Jackson 직렬화 방식으로 보내면 KIS GW가 EGW00202 반환 (필드 순서·포맷 민감성). `KisHttpClient.post()`의 body 파라미터가 `Object`이므로 String 전달 가능

### 체결 조회 API (TTTS3035R, KisExecutionAdapter)
- 파라미터: `CANO`, `ACNT_PRDT_CD`, `PDNO="%"`, `ORD_STRT_DT`, `ORD_END_DT` (INQR_STRT/END_DT 아님!)
- 추가 필수 파라미터: `SLL_BUY_DVSN=00`, `CCLD_NCCS_DVSN=00`, `OVRS_EXCG_CD=NASD`, `SORT_SQN=DS`, `ORD_DT=""`, `ORD_GNO_BRNO=""`, `ODNO=""`
- 응답 lowercase 필드: `pdno`, `sll_buy_dvsn_cd`(01=매도,02=매수), `ft_ccld_qty`, `ft_ccld_unpr3`, `ft_ccld_amt3`, `odno`

### kis-trade-mcp (localhost:3001)
- `open-trading-api/MCP/Kis Trading MCP` 소스, SSE 모드 Docker 컨테이너
- docker run 시 KIS 자격증명 환경변수 필수: `KIS_APP_KEY`, `KIS_APP_SECRET`, `KIS_HTS_ID`, `KIS_ACCT_STOCK` (kista `.env`의 `KIS_ACCOUNT_NO` 값 — 변수명 다름 주의)
- 재시작/문제 발생 시: `commands.md`의 `kis-trade-mcp 재시작` 섹션 참고

### 기간손익 API (TTTS3039R, KisProfitAdapter)
- TR ID: `TTTS3039R` (주의: `TTTS3027R` 아님 — 오기 주의)
- PATH: `/uapi/overseas-stock/v1/trading/inquire-period-profit`
- 파라미터: `CANO`, `ACNT_PRDT_CD`, `OVRS_EXCG_CD=NASD`, `NATN_CD=""`, `CRCY_CD=USD`, `PDNO=""`, `INQR_STRT_DT`, `INQR_END_DT` (YYYYMMDD), `WCRC_FRCR_DVSN_CD=01`(외화), `CTX_AREA_FK200=""`, `CTX_AREA_NK200=""`
- 응답: `output1[]`(종목별 손익 — `trad_day`, `ovrs_pdno`, `slcl_qty`, `pchs_avg_pric`, `avg_sll_unpr`, `ovrs_rlzt_pfls_amt`, `pftrt`), `output2`(요약 — `ovrs_rlzt_pfls_tot_amt`, `tot_pftrt`)

### 체결기준현재잔고 API (CTRP6504R, KisPortfolioAdapter)
- TR ID: `CTRP6504R` (실전) / `VTRP6504R` (모의)
- PATH: `/uapi/overseas-stock/v1/trading/inquire-present-balance`
- 파라미터: `CANO`, `ACNT_PRDT_CD`, `WCRC_FRCR_DVSN_CD=02`(외화), `NATN_CD=000`(전체), `TR_MKET_CD=00`(전체), `INQR_DVSN_CD=00`(전체)
- 응답: `output1[]`(종목별 잔고 — `pdno`, `cblc_qty13`, `avg_unpr3`, `ovrs_now_pric1`, `frcr_evlu_amt2`, `evlu_pfls_amt2`, `evlu_pfls_rt1`), `output3`(요약 — `tot_asst_amt`, `tot_evlu_pfls_amt`, `evlu_erng_rt1`)

### KIS 응답 Ticker 필터링 패턴
- 응답 stream에서 enum 외 종목 제거: `.flatMap(o -> Ticker.tryParse(o.pdno()).map(t -> new Foo(t, ...)).stream())`
- `Ticker.tryParse`가 empty인 항목은 자동 제외 (silent drop) — 필요 시 `log.warn("KIS 응답 Ticker 외 종목 무시: {}", pdno)` 추가
- 어댑터 단위 테스트: fixture에 `pdno="AAPL"` 행 추가 → 결과 List 크기·내용으로 필터 동작 검증

### KIS 어댑터 공통 파싱 헬퍼 (KisResponseParser)
- `adapter/out/kis/KisResponseParser` — package-private 유틸: `parseBd(String)`, `parseIntSafe(String)`, `parseDirection(String)`
- 어댑터 내부에 파싱 헬퍼 직접 정의 금지 — KisResponseParser 사용
- `parseIntSafe`: `(int) Double.parseDouble()` 경유 — KIS 응답이 `"5.0"` 같은 소수 형식일 수 있음
- `KisAccountAdapter.fetchMargin()`: KisMarginPort 주입 경유 (직접 TTTC2101R 호출 아님) — USD 행 필터는 `currency()` 필드 기준
- `MarginItem` 필드: `currency()` / `integratedOrderableAmount()` — KIS API 필드명(`crcy_cd` 등) 아님

### KIS 어댑터 상수 사용 규칙
- `"NASD"`, `"AMEX"`, `"NYSE"` 리터럴 직접 작성 금지 → `KisExchangeRegistry`의 `ovrsExcgCd(ticker)`/`excd(ticker)`/`defaultUsExchange()` 경유
- `"USD"`, `"미국"` 필터값은 현재 리터럴 유지 (대응 enum 없음)

### 복수종목 현재가 (KisPriceAdapter)
- `getPrices(List<Ticker>, Account)` — 복수검색 API(`HHDFS76220000`, `/uapi/overseas-price/v1/quotations/multprice`) 단건 호출로 구현
  - 파라미터: `AUTH=""`, `NREC=종목수`, `EXCD_01`/`SYMB_01` … `EXCD_10`/`SYMB_10` (2자리 zero-padded, 최대 10종목)
  - 응답: `output.nrec`, `output2[]`(종목별) — `symb`(종목코드), `last`(현재가) 필드 사용
  - `Ticker.tryParse(symb)`로 enum 외 종목 silent drop
- `getPrice(Ticker, Account)` — 단건 API(`HHDFS00000300`) 유지
- KIS 거래소 코드 두 체계 혼용: `OVRS_EXCG_CD` (주문·체결·잔고 API) = 4자리 `NASD`/`AMEX`/`NYSE`, `EXCD` (시세 API) = 3자리 `NAS`/`AMS`/`NYS`
- `KisExchangeRegistry`(adapter/out/kis): `Ticker → (ovrsExcgCd, excd)` 매핑 전담. `ovrsExcgCd(ticker)`/`excd(ticker)`/`defaultUsExchange()`(="NASD", "미국 전체" 잔고·손익 조회용) 제공 — TQQQ=NASD/NAS, SOXL/USD/MAGX/FNGU/BULZ=AMEX/AMS
- KIS 응답 모델(`PresentBalanceResult.Item`, `PeriodProfitResult.Item`)의 `exchangeCode: String`은 수신값이므로 enum 변환 대상 아님
- **KIS 예약주문 API(`TTTT3014U`) 사용 금지** — 지정가(ORD_DVSN=00)만 지원, LOC/MOC 전송 시 EGW00202 반환. 일반 주문 API(`TTTT1002U`/`TTTT1006U`)가 프리마켓·정규장·애프터마켓 전 구간에서 LOC/MOC 모두 지원하므로 예약주문 API 불필요 — kista에서 완전 제거됨

