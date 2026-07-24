## KIS API

**공식 문서**: https://apiportal.koreainvestment.com/apiservice
**오픈트레이딩 예제**: https://github.com/koreainvestment/open-trading-api

KIS API 파라미터·응답 필드·TR ID는 공식 문서가 SSOT. 아래는 kista 코드베이스에 특화된 패턴과 운영 중 발견된 주의사항만 기록.

### 토큰·인증

- 모든 KIS 호출은 `KisHttpClient` 경유 (공통 헤더: `authorization`, `appkey`, `appsecret`, `tr_id`, `custtype: P`)
- `KisHttpClient.buildHeaders(String trId, Account account)` — Account 계좌별 자격증명으로 헤더 구성
- `KisAuthApi.getToken(UUID accountId, String appKey, String appSecret)` — account_id 기반 독립 토큰 캐시 (`broker_tokens` PK = account_id UUID). `KisHttpClient`가 **구체 타입으로 직접 주입**받음 (KisTokenPort 삭제됨 — 어댑터 패키지 내부 협력)
- 토큰 관리는 `KisAuthApi`만 담당; 만료 1분 전부터 재발급 (경계값 EGW00123 방지), `DoubleCheckedTokenCache`로 동시 요청 경합 차단
- `KisHttpClient`는 401이 발생한 요청의 access token을 함께 전달해 **DB에 그 토큰이 아직 저장된 경우에만** `INVALIDATED_TOKEN`으로 조건부 갱신한 뒤 최신 토큰으로 1회 재시도한다. 느리게 도착한 stale 401이 동시 요청이 이미 재발급한 신규 토큰을 무효화하지 않도록 `accountId + rejectedAccessToken` 일치가 필수다.
- **`KisAuthApi`는 `KisHttpClient` 빈 미주입** — `RestTemplate` 직접 사용 (순환 의존 방지, 정적 헬퍼 `splitAccountNo`/`buildHeaders`만 참조)
- `BrokerConnectionTestPort` 구현 (`verifyCredentials`/`verifyAccount`): `verifyCredentials(appKey, secretKey, accountId)` — accountId=null이면 캐시 저장 생략(등록 전 사전 검증), 성공 토큰은 90초 단기 캐시(EGW00133 회피)
- `verifyAccount(appKey, secretKey, accountNo)` — TTTC2101R로 계좌 실소유 검증 후 `null` 반환 (KIS는 brokerAccountCode 없음), 실패 시 `Account.InvalidBrokerKeyException`
- 모든 KIS 포트 인터페이스에 `Account account` 파라미터 (V2) — `getBalance(Account)`, `place(Order, Account)`, `isMarketOpen(LocalDate, Account)` 등
- KIS 포트 인터페이스에 `token` 파라미터 없음 — 서비스 레이어에서 token 관리·전달 금지
- Base URL: `https://openapi.koreainvestment.com:9443`

### 주요 오류 코드

- `EGW00202` "GW라우팅 중 오류가 발생했습니다" — 세 가지 원인:
  1. **미국 공휴일에 주문 접수**: 휴장일 미감지로 주문 시도 → KIS 거부 (과거 KIS 휴장조회 404 폴백 사례 — 현재는 Alpaca 캘린더 DB 기반)
  2. **LOC 주문에 가격 "0" 전송**: LOC(장마감지정가)는 실제 limit price 필수. `"0"` 전송 시 "$0 이하 체결" 불가 조건으로 판단해 거부 (과거 `formatPrice(LOC,price)="0"` 버그, 수정 완료)
  3. **주문 body를 Map(compact JSON)으로 전송**: KIS GW는 raw JSON String 포맷만 허용 — `KisOrderApi.place()`는 `String.format()` 방식 사용 (LinkedHashMap → Jackson 직렬화 금지)
  - 재시도 로직 없음 — 원인 제거로만 해결
- `EGW00123` — 토큰 만료 경계값 오류 (만료 1분 전 재발급으로 방지 중, `KisAuthApi`)
- `APBK0988` "주문수량이 가능수량보다 큽니다" — 매도 주문 수량 > 판매가능수량 또는 매수 금액 > 실가용자금. 스케쥴러는 `TradingOrderBudgetAllocator`에서 BUY·SELL을 독립 검증하며, BUY는 cap·correction 반영 최종 총액과 기존 PLANNED 금액을, SELL은 계좌·거래일·종목별 기존 PLANNED/PLACED 예약 수량을 각각 반영한다. 수동 SELL도 기존 예약 수량과 신규 수량의 합을 검증한다.

### Alpaca Calendar API (`/v2/calendar`, AlpacaCalendarAdapter)
- 지원 범위: 1970~2029년 — 2026년 기준 최대 3년 선제 적재 가능
- 스케쥴러: 1월 1일 00:00 KST 3년치(`year`~`year+2`) 적재 / 매월 1일 01:00 KST 당월 최신화 (`MarketCalendarRefreshScheduler`)
- `refreshCalendar(year)`: 연간 전체 교체(`replaceByYear`) / `refreshMonth(year, month)`: 월별 교체(`replaceByMonth`)

### 응답 필드명 대소문자 주의
- **해외주식 API 응답은 lowercase** (`ovrs_pdno`, `ovrs_cblc_qty`, `frcr_evlu_amt2` 등)
- 국내주식 API는 UPPERCASE — 혼동 주의, `@JsonProperty` 값 반드시 소문자로

### 노출된 KIS live REST 엔드포인트 (신규 추가 전 확인)
- `GET /api/accounts/{accountId}/prices?tickers=TQQQ,SOXL,USD` — `StatisticsController`, `Map<Ticker,BigDecimal>` 응답(**전일종가**, current 아님 — 전략 생성 화면 최소 시드 기준가와 통일), `accounts/[[...path]]` catch-all로 kista-ui 프록시됨
- `GET /api/accounts/{accountId}/margin` — `StatisticsController`, `List<MarginItem>` 응답, USD 예수금은 `currency=="USD"` 행의 `integratedOrderableAmount`
- `GET /api/market/holidays?year=YYYY&month=MM` — `MarketHolidayController`, 해당 월 미국 시장 휴장일 날짜 목록(`List<String>`, ISO 형식), JWT 인증 필요

### 잔고 조회 주의사항 (TTTC2101R)
- `usdDeposit` = `itgr_ord_psbl_amt`(통합주문가능금액) — `frcr_dncl_amt_2`(환전된 외화만) 대신 사용 (원화 자동 환전 포함)
- **필터 기준: `natn_name == "미국"` 단일 행** — `crcy_cd == "USD"`로 필터하면 동일 값의 중복 행이 여러 개 반환됨
- `MarginItem` 도메인 모델: `currency`, `integratedOrderableAmount` 2개 필드만
- API 파라미터 불확실 시 `kis-coding-mcp`의 `search_overseas_stock_api` + `read_source_code`로 공식 확인

### 주문 API (KisOrderApi)
- 미국 매수 TR ID: `TTTT1002U`, 미국 매도: `TTTT1006U` (일본은 TTTS0308U/0307U — 혼동 주의)
- `ORD_DVSN` 코드: LOC(장마감지정가)=`34`, MOC(장마감시장가)=`33`, LOO(장개시지정가)=`32`, 지정가=`00`
- MOC(장마감시장가) 주문 시 `OVRS_ORD_UNPR="0"`, **LOC(장마감지정가)는 실제 limit price 필수**
- **KIS 가격 파라미터 포맷팅 SSOT**: `KisResponseParser.formatPrice(type, price)` — MOC(시장가)만 `"0"`, LOC/LIMIT(지정가)는 `setScale(2, HALF_UP).toPlainString()`. `price.toPlainString()` 직접 사용 금지 (scale=4 값 전송 시 KIS 오류)
- **주문 body는 반드시 raw JSON String**: `String.format("""...""", ...)` 방식으로 직접 구성 — `Map<String, String>` + RestTemplate Jackson 직렬화 방식으로 보내면 KIS GW가 EGW00202 반환 (필드 순서·포맷 민감성)
- **KIS 예약주문 API(`TTTT3014U`) 사용 금지** — 지정가(ORD_DVSN=00)만 지원, LOC/MOC 전송 시 EGW00202 반환. 일반 주문 API가 프리마켓·정규장·애프터마켓 전 구간에서 LOC/MOC 모두 지원하므로 예약주문 API 불필요 — kista에서 완전 제거됨

### KIS 어댑터 공통 파싱 헬퍼 (KisResponseParser)
- `adapter/out/kis/KisResponseParser` — package-private 유틸: `parseBd(String)`, `parseIntSafe(String)`, `parseDirection(String)`
- 어댑터 내부에 파싱 헬퍼 직접 정의 금지 — KisResponseParser 사용
- `parseIntSafe`: `(int) Double.parseDouble()` 경유 — KIS 응답이 `"5.0"` 같은 소수 형식일 수 있음
- `KisTradingApi.getMargin()`: MarginPort 구현 (TTTC2101R) — USD 행 필터는 `currency()` 필드 기준
- `MarginItem` 필드: `currency()` / `integratedOrderableAmount()` — KIS API 필드명(`crcy_cd` 등) 아님

### KIS 어댑터 상수 사용 규칙
- `"NASD"`, `"AMEX"`, `"NYSE"` 리터럴 직접 작성 금지 → `KisExchangeRegistry`의 `ovrsExcgCd(ticker)`/`excd(ticker)`/`defaultUsExchange()` 경유
- `"USD"`, `"미국"` 필터값은 현재 리터럴 유지 (대응 enum 없음)

### 복수종목 현재가 (KisPriceApi)
- `getPrices(List<Ticker>, Account)` — 복수검색 API(`HHDFS76220000`, `/uapi/overseas-price/v1/quotations/multprice`) 단건 호출로 구현
  - 파라미터 패턴: `EXCD_01`/`SYMB_01` … `EXCD_10`/`SYMB_10` (2자리 zero-padded, 최대 10종목)
  - 응답: `output2[]` 종목별 — `symb`(종목코드), `last`(현재가) 필드 사용
- `getPrice(Ticker, Account)` — 단건 API(`HHDFS00000300`) 유지
- KIS 거래소 코드 두 체계 혼용 주의: `OVRS_EXCG_CD` (주문·체결·잔고 API) = 4자리 `NASD`/`AMEX`/`NYSE`, `EXCD` (시세 API) = 3자리 `NAS`/`AMS`/`NYS`
- `KisExchangeRegistry`(adapter/out/kis): `Ticker → (ovrsExcgCd, excd)` 매핑 전담 — TQQQ=NASD/NAS, SOXL/USD/MAGX=AMEX/AMS

### 전일종가(prevClose) 조회 — KisPriceApi.getPriceSnapshot()/getPriceSnapshots()

- 단건(`HHDFS00000300`)·복수종목(`HHDFS76220000`) 응답 모두 `base`(전일종가) 필드를 `last`(현재가)와 함께 반환 — **별도 API 호출 없이 `base` 필드를 그대로 prevClose로 사용** (빈값이면 current로 fallback)
- 과거엔 "`base`는 장 시작 전엔 하루 더 과거 종가일 수 있다"는 결함 회피를 위해 종목별로 `dailyprice`(`HHDFS76240000`)를 별도 호출해 확정 종가를 재조회했으나, 정확도보다 단순성(종목당 API 호출 1회)을 택해 제거함 — 운영 중 이상 종가(개장 전 시간대)가 관측되면 이 트레이드오프부터 의심할 것
- 2026-07-24 마감 리포트 종가 정확도 문제(라이브 current가 종가로 오인됨)로 `getClosingPrice(s)`를 신규 추가하며 dailyprice를 재도입했다 — 단, 위에서 제거된 `getPrevClose(s)`(전일종가, 미리보기·통계 등 핫패스 공용)와는 별개 메서드로 격리해 호출량 증가가 마감 리포트 1일 1회로만 한정되도록 했다. 응답 봉 날짜가 기대 거래일과 다르면 라이브 현재가로 자동 fallback한다(`KisPriceApi.fetchConfirmedClose`).
- **전략 생성 화면과 실제 매매의 기준가 통일**: `AccountStatisticsService.getPrices()`(`GET /prices` 티커 목록)와 `strategySeedPreview()`(최소 시드 미리보기) 모두 `BrokerPricePort.getPrevClose(s)`를 사용 — `TradingPreviewService`·실제 매매 실행(`InfinitePosition.averagePrice()`, holdings==0→prevClose)과 동일한 소스로 맞춰, "전략 생성 시 본 기준가"와 "실제 첫 주문가"가 항상 일치하도록 함. 순수 현재가(`getPrice`/`getPrices`)는 이 두 화면 어디에도 쓰지 않음
- **`BrokerPricePort`에 `getPrevClose`/`getPrevCloses` 전용 메서드 존재**: `getPriceSnapshot(s)`(현재가+전일종가 조합)와 별개로, 전일종가만 필요하면 이걸 사용 — KIS는 응답이 한 API에 묶여 있어 내부적으로 `getPriceSnapshot(s)`를 재사용(호출 절감 없음)하지만, Toss는 현재가 API(`/api/v1/prices`) 호출을 완전히 생략하고 캔들 API만 호출하므로 실질적 절감이 있음 (`toss-api.md` "가격 조회 API 3분리" 참고)

### KIS 응답 Ticker 필터링 패턴
- 응답 stream에서 enum 외 종목 제거: `.flatMap(o -> Ticker.tryParse(o.pdno()).map(t -> new Foo(t, ...)).stream())`
- `Ticker.tryParse`가 empty인 항목은 자동 제외 (silent drop) — 필요 시 `log.warn("KIS 응답 Ticker 외 종목 무시: {}", pdno)` 추가
- 어댑터 단위 테스트: fixture에 `pdno="AAPL"` 행 추가 → 결과 List 크기·내용으로 필터 동작 검증

### kis-trade-mcp (localhost:3001)
- `open-trading-api/MCP/Kis Trading MCP` 소스, SSE 모드 Docker 컨테이너
- docker run 시 KIS 자격증명 환경변수 필수: `KIS_APP_KEY`, `KIS_APP_SECRET`, `KIS_HTS_ID`, `KIS_ACCT_STOCK` (kista `.env`의 `KIS_ACCOUNT_NO` 값 — 변수명 다름 주의)
- 재시작/문제 발생 시: `docker-infra.md`의 `kis-trade-mcp 재시작` 섹션 참고
