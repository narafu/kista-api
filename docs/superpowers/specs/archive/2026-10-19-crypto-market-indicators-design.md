# 코인 시장 지표(김프·펀딩비·OI) 대시보드 API — 설계

## 의도

코인 DCA 사용자가 대시보드에서 "지금 코인 시장이 과열인가"를 한눈에 판단하도록, 국내 현물 과열(김치 프리미엄)과 글로벌 레버리지 과열(선물 펀딩비·미결제약정)을 공포탐욕지수 카드 옆에 보여준다. 이 문서는 kista-api(trading-core) 범위만 다룬다 — kista-ui 카드·차트, 인프라 작업은 각 레포 세션에서 별도로 진행한다(아래 "UI 핸드오프").

## 목표

- 코인 DCA 지원 종목 전체(현재 BTC·ETH — `StrategyTicker` 중 `Market.CRYPTO_KRW`)에 대해 세 지표의 현재값 + 일 단위 추이를 한 엔드포인트로 제공
  - 김치 프리미엄(공식 환율 기준·테더 기준 두 값 + 테더 프리미엄): 현재값 + 최근 N일(기본·최대 200) 일별 추이
  - 펀딩비: 현재값(+다음 정산 시각) + 최근 N일 일평균 추이
  - 미결제약정(OI): 최근 30일 일별 추이(USD 명목가)
- DB 테이블·스케줄러·마이그레이션 없이 외부 공개 API 조회 + 서버 메모리 캐시로 구현

## 비목표

- OI 30일 초과 추이(스냅샷 저장) — 사용자가 향후 고려로 보류. 필요해지면 `trading_ref` 일 스냅샷 테이블 + 스케줄러로 확장
- 장중(시간 단위) 해상도 — 일 단위만
- 김프 알림·DCA 김프 가드(`DcaCondition`에 지표 추가) — 카드 사용 후 결정. 이 설계는 그 재사용을 전제하지 않는다(필요해지면 trading 쪽 어댑터 별도 — 공포탐욕지수가 root market·trading-core trading에 각각 어댑터를 둔 선례)
- Binance 계좌 연동·주문 — 국내 미신고 거래소라 범위 밖

## 안티골

- `StrategyTicker`에 `USDT` 추가 금지 — sharedkernel enum은 DB 문자열·`/api/meta`·DCA 등록 대상이라 매매 가능 종목처럼 새어 나간다
- 대시보드 조회가 코인 DCA 실행의 Upbit 공개 시세 호출을 rate limit(429)으로 밀어내면 실패 — 같은 서버 IP를 공유하므로 서버 측 캐시 필수
- 한 소스(예: Binance 선물) 장애가 다른 지표(김프)까지 비우면 실패 — 지표별 격리

## 제약

- 외부 호출 가능성 실측 완료(2026-10-19, OCI 춘천 `kista-server`에서 모두 HTTP 200): `fapi.binance.com/fapi/v1/ping`, `/fapi/v1/fundingRate`, `/futures/data/openInterestHist?period=1d&limit=30`, `api.binance.com/api/v3/klines`, `api.upbit.com/v1/ticker?markets=KRW-USDT`, `api.frankfurter.dev/v1/<기간>?base=USD&symbols=KRW`
- 모든 호출은 키 없는 공개 API
- kista-trading은 단일 인스턴스 — JVM 메모리 캐시로 충분(Redis 불필요)
- Binance `openInterestHist`는 최근 30일만 제공 → OI 추이는 30일 고정
- 프로젝트 규칙: `docs/agents/constraints.md`(주석·네이밍·REST·ProblemDetail), REST 클라이언트는 `new-adapter` skill 3파일 패턴

## 결정된 사항

### 배치 (trading-core)
- 엔드포인트·서비스는 `com.kista.tradingcore.stats`(조회 전용 소비자). kista-ui가 이미 trading-core(8081)를 직접 프록시하고, Upbit 종목 매핑(`UpbitMarketRegistry`)·공개 시세 호출이 trading-core broker에 있어 "DCA 지원 종목 = 지표 대상 종목"이 같은 기준을 따른다
- Frankfurter 과거 환율 어댑터도 같은 방식으로 `stats/adapter/out/frankfurter`(stats 소유 출력 포트). 현재 환율은 broker `ExchangeRatePort`(Toss) 재사용
- Binance 공개 시세 어댑터는 `stats/adapter/out/binance`(선례: `stats/adapter/out/alpaca/AlpacaCandleAdapter` — stats 소유 출력 포트를 stats 어댑터가 구현). Binance 심볼은 `ticker.name() + "USDT"`(BTCUSDT·ETHUSDT) — 별도 레지스트리 없음
- Upbit 시세는 broker `CryptoExchanges.of(Broker.UPBIT)`의 `CryptoExchangePort` 경유(broker `adapter/out`은 모듈 밖 비공개). 코인 KRW 현재가·완성 일봉은 기존 `getCurrentPrices`·`getCompletedDailyCandles` 재사용

### 계산식
- 김치 프리미엄은 두 기준을 모두 제공한다(사용자 결정 — 익숙한 공식 환율 숫자와 코인 자체 과열을 분해해 보기 위함):
  - 공식 환율 김프(%) = (Upbit `KRW-<코인>` ÷ (Binance `<코인>USDT` × USD/KRW 공식 환율) − 1) × 100 — 일반 김프 사이트와 같은 기준
  - 테더 김프(%) = (Upbit `KRW-<코인>` ÷ (Binance `<코인>USDT` × Upbit `KRW-USDT`) − 1) × 100 — 원화 유출 제약에서 오는 프리미엄을 뺀 코인 자체 국내 과열도
  - 테더 프리미엄(%) = (Upbit `KRW-USDT` ÷ USD/KRW 공식 환율 − 1) × 100 — 종목 무관 단일 값. 관계식 (1+공식) = (1+테더 김프) × (1+테더 프리미엄) — 곱셈 관계라 UI가 "≈ 합"으로 설명할 때 근사임을 감안
  - 공식 환율 출처(사용자 결정): 현재값 = 기존 Toss `ExchangeRatePort.getExchangeRate().midRate()`(매매기준율, 실시간), 추이 = Frankfurter(ECB 기준환율, 무료·키 없음, `GET https://api.frankfurter.dev/v1/<시작>..<끝>?base=USD&symbols=KRW` 1회로 기간 조회 — 춘천 서버에서 200 확인). 출처가 달라 오늘 값과 추이 사이 미세 단차 허용
  - Frankfurter는 주말·휴일 값이 없다 → 직전 영업일 값으로 채운다(창 시작 이전 영업일 값이 필요하므로 조회 시작일을 며칠 앞당긴다)
  - 현재값: 현재가(Upbit 코인·Upbit USDT·Binance 현물) + Toss 환율
  - 추이: 일봉 종가 기준, **UTC 날짜 키로 조인**(인덱스 정렬 금지). Upbit 일봉은 KST 09:00(=UTC 00:00) 시작이라 `CryptoCandle.kstDate`가 곧 UTC 날짜, Binance kline `openTime`의 UTC 날짜와 일치. Binance kline은 진행 중 봉을 포함하지만 Upbit 쪽이 완성 봉만 주므로 시계열 날짜 교집합만 쓰면 자동 제외. 환율 일자도 같은 날짜 키로 매칭
- 펀딩비: 현재값 = `/fapi/v1/premiumIndex`의 `lastFundingRate`(+`nextFundingTime`), 추이 = `/fapi/v1/fundingRate`(8시간 간격) 레코드를 `fundingTime`의 UTC 날짜로 묶은 일평균. 비율 원값(예: 0.0001) 그대로 반환 — % 표기는 UI 몫
- OI: `/futures/data/openInterestHist?period=1d&limit=30`의 `sumOpenInterestValue`(USD 명목가) 일별 추이. 별도 현재값 호출 없음 — 최신 포인트가 현재값 역할

### API
- `GET /api/crypto-market-indicators?days=200` (비인증 공개 — trading-core `TradingSecurityPolicy` permitAll, 기본 200, 범위 1~200 — 벗어나면 400). `days`는 김프·펀딩비 추이에만 적용, OI는 항상 최근 30일
- 응답(전용 Response DTO, 섹션별 nullable):
  - `asOf`: 계산 시각
  - `usdtPremium{current, history[{date, value}]}`: 테더 프리미엄(종목 무관)
  - `tickers[]`: `ticker`, `kimchiPremium{current{official, tether}, history[{date, official, tether}]}`(환율 실패 시 `official`만 null), `fundingRate{current, nextFundingTime, history[{date, value}]}`, `openInterest{history[{date, valueUsd}]}`
- 실패 격리: 종목·지표 단위로 실패를 잡아 해당 섹션만 `null` + warn 로그. 환율(Toss·Frankfurter) 실패는 공식 환율 김프 값·`usdtPremium`만 비우고 테더 김프는 유지(`FearGreedService` 소스별 try/catch 패턴). 모든 종목의 모든 섹션이 실패해도 200 + 전부 null(미캐시)
- `Cache-Control` 공개 캐시 10분(`FearGreedController` 패턴)

### 캐시
- 서비스 내 JVM 메모리 캐시, TTL 10분. 캐시 단위는 최대 범위(200일) 원본 계산 결과 1벌이고 `days`는 캐시된 추이를 잘라서 응답한다 — `days` 값마다 외부 호출이 늘지 않도록(내 판단). 실패한 섹션이 섞인 응답도 캐시할지는 빌더 판단이되, 전체 실패(200 + 전부 null)는 정상 TTL로 캐시하지 않는다 (선례: `TossMarketCalendarCache` — 예외·empty 미캐싱). 비인증 공개 전환 후 장애 중 fan-out 억제를 위해 전체 실패 결과만 별도 키에 1분 캐시

### 지표 추가 (2026-10-20)
- 무료 외부 온체인 API(Bitbo·BGeometrics·Coin Metrics)는 서비스 화면 공개가 유료이거나 비상업 라이선스라 쓰지 않고, 이미 연동한 Binance 공개 API로 직접 계산한다
- 장기 밸류에이션(`tickers[].valuation{current, history[{date, close, wma200, wma200Multiple, mayerMultiple, athDrawdownPercent}]}`): Binance 현물 일봉(상장 2017-08-17부터, `startTime` 1000봉 페이징)으로 200주 이동평균(1400일 단순평균)·종가/200WMA 배수·Mayer Multiple(종가/200일 이동평균)·ATH(일봉 종가) 대비 하락률을 domain `CryptoValuations`가 계산. 진행 중 오늘 봉 제외, 각 일자는 그날까지의 봉만 사용, 이력 부족 시 해당 값 null. `current`는 직전 완성 일봉 기준(실시간 아님). 추이는 `days` 적용
- 롱/숏 계좌 비율(`tickers[].longShortRatio{longAccount, longShortRatio, history[{date, longAccount, longShortRatio}]}`): `/futures/data/globalLongShortAccountRatio?period=1d&limit=30`. 계좌 수(사람) 기준이라 포지션 규모(돈) 기준인 펀딩비와 다르다. Binance가 30일만 제공해 `days`와 무관하게 30일
- 장기 일봉 캐시: 메인 10분 캐시와 별도로 종목별 고정 키에 다음 UTC 자정까지. 김프 추이도 이 일봉을 공유한다(기존 `limit=201` 별도 호출 제거 — 조회 실패 시 김프·밸류에이션이 함께 null) — 완성 봉은 하루 한 번만 늘고, 종목당 4페이지 호출을 10분마다 반복할 이유가 없다. 날짜를 키에 넣지 않는다(`TtlResultCache`는 1000건 이상일 때만 만료 청소)

### 반감기 카운트다운 추가 (2026-10-20)
- `halving{currentHeight, nextHalvingHeight, blocksRemaining, estimatedAt}`(최상위, 종목 무관, 실패 시 null): mempool.space `GET /api/blocks/tip/height`(무인증, text 숫자)로 현재 높이를 받아 다음 210,000 배수까지 남은 블록과 조립 시각 + 남은 블록 × 10분 예상 시각을 계산. 별도 캐시 없이 메인 10분 캐시 공유

### Coinbase 프리미엄 추가 (2026-10-20)
- 의도: 미국 현물 수요 지표. 김프(한국 수요)와 대비해 읽는다. UI는 김프 카드에 합치지 않고 별도 카드(김프 바로 옆) — 값 크기(김프 수 %, Coinbase ±0.1~0.3%)·비교 기준이 다르고 지표별 실패를 격리하기 위함(사용자 결정)
- 계산식: 테더 보정 Coinbase 프리미엄(%) = (Coinbase `<코인>-USD` ÷ (Binance `<코인>USDT` × Coinbase `USDT-USD`) − 1) × 100. 보정 필수 — 2026-10-19 실측 BTC 보정 없이 −0.084%, `USDT-USD` 0.99882로 보정하면 +0.034%로 부호가 바뀐다
- 대상: 김프와 같이 `StrategyTicker` 중 `Market.CRYPTO_KRW`(BTC·ETH). Coinbase 상품 ID는 `<코인>-USD`(`USDT`는 포트에 자산 코드 문자열로 넘긴다 — `StrategyTicker`에 넣지 않는다)
- 소스: Coinbase Exchange 공개 API(무인증) — 현재가 `GET https://api.exchange.coinbase.com/products/<상품>/ticker`의 `price`, 일봉 `GET .../products/<상품>/candles?granularity=86400`(start/end 없이 최신 300봉, 응답 `[시작 UTC epoch초, low, high, open, close, volume]` 최신 순 — Binance kline과 필드 순서가 다름). 추이는 최대 200일이라 300봉 1회로 충분해 페이징하지 않는다. 운영 egress 실측 완료(2026-10-20, `kista-server`에서 ticker·candles 모두 HTTP 200)
- 추이: 김프와 같은 방식 — 일봉 종가, UTC 날짜 키 조인(Coinbase 코인·Coinbase USDT·Binance 장기 일봉 교집합), 진행 중 오늘 봉 제외, `days` 적용. Binance 쪽은 김프·밸류에이션과 같은 장기 일봉 캐시를 공유하고, Binance 현물 현재가도 김프와 공유(조립당 종목별 1회)
- 응답: `tickers[].coinbasePremium{current, history[{date, value}]}`(실패 시 null, % 값)
- 실패 격리: Coinbase `USDT-USD` 실패 → 전 종목 이 섹션만 null, 종목 Coinbase 실패 → 그 종목 이 섹션만 null, Binance 장기 일봉·현재가 실패 → 그 종목 김프와 함께 null. warn 로그. 메인 10분 캐시 공유, 다른 지표 영향 없음
- 쓰지 않는 것: Coinbase International(INTX) 무기한 선물 펀딩비·OI(`trading_state: PAUSED`, 2026-10-01에서 데이터 정지), 미국 CDE 선물(이력 엔드포인트 없음, Binance 대비 규모 작음)

### 시장 전체 지표 추가 — BTC 도미넌스·알트코인 시즌·Hash Ribbons (2026-10-20)
- 의도: 사이클 위치 판단 보조. 도미넌스·알트 시즌은 "자금이 BTC에 몰렸나 알트로 퍼졌나", Hash Ribbons는 채굴자 항복(바닥권) 신호. 셋 다 종목 무관이라 `halving`처럼 **최상위** 섹션(`tickers[]` 아님)
- 소스 실측(2026-10-20, 로컬·`kista-server` 모두 HTTP 200):
  - CMC 공식 Pro API(`pro-api.coinmarketcap.com`, 헤더 `X-CMC_PRO_API_KEY` 필수 — 없으면 `error_code 1002 "API key missing."`). Basic 무료 플랜: 월 15,000 크레딧·분당 50회
    - `GET /v1/global-metrics/quotes/latest` → `data.btc_dominance`(%, 약 5분 갱신, 1크레딧)
    - `GET /v1/global-metrics/quotes/historical?interval=daily&count=<n>` → `data.quotes[]{timestamp(UTC 00:00), btc_dominance}` — 무료 키로 365일 동작(4크레딧). 시간 오름차순
    - `GET /v1/altcoin-season-index/latest` → `data.altcoin_index`(0~100 정수, 1크레딧)
    - 공식 이력(`/v1/altcoin-season-index/historical`)은 `timeframe` 7d/30d/90d만 허용(`1y`·`180d` 등 "Invalid value", `time_start` 무시) → **쓰지 않는다**
  - CMC 비공식 웹 API(무인증): `GET https://api.coinmarketcap.com/data-api/v3/altcoin-season/chart?start=<epoch초>&end=<epoch초>` → `data.points[]{altcoinIndex(문자열), altcoinMarketcap(문자열), timestamp(epoch초 문자열)}`, 일별 + 마지막 점은 실시간(오늘). 2024-03부터 제공
  - mempool.space(무인증): `GET /api/v1/mining/hashrate/2y` → `hashrates[]{timestamp(UTC 00:00 epoch초), avgHashrate(H/s, double)}` 일별 약 730개(`1y`는 365개라 60일 SMA 워밍업 부족)
- 알트 시즌 이력은 비공식 API 사용(사용자 결정 — 365일 이력 우선, 문서화되지 않은 API라 형식 변경·차단 위험 감수). 현재값은 공식 `latest`라 비공식이 깨져도 현재값은 유지된다
- 계산:
  - 도미넌스 이력: `quotes[].timestamp`의 UTC 날짜 키, 값은 `btc_dominance` 그대로(%)
  - 알트 시즌 이력: `timestamp` UTC 날짜 키로 묶어 날짜별 마지막 점, 오늘(UTC) 점 제외(현재값은 latest가 담당), 정수 값
  - Hash Ribbons: domain 순수 계산(`CryptoValuations` 류). 해시레이트는 어댑터가 EH/s(÷10¹⁸)로 변환해 넘기고, 각 일자까지의 30일·60일 단순이동평균(이력 부족 시 null), 상태 `CAPITULATION`(sma30 < sma60) / `RECOVERY`(그 외), `buySignal`은 `CAPITULATION` → `RECOVERY` 전환 후 BTC 종가 10일 SMA > 20일 SMA(점 개수 기준, 20점 미만·당일 가격 없으면 false)가 처음 충족되는 날만 true — 전환일에 이미 충족이면 그날, 아니면 RECOVERY가 유지되는 동안 첫 충족일로 지연, 충족 전에 다시 `CAPITULATION`이면 취소(2026-10-20 사용자 결정: 원본 정의로 변경, 가격 조건 충족일까지 지연). 가격은 캐시된 Binance BTC 장기 일봉 재사용, 실패 시 섹션 null. 오늘(UTC) 점이 있으면 제외. 2y 전체로 SMA를 계산한 뒤 최근 `MAX_DAYS`(365)만 남긴다 → 365일 전 구간 SMA 값이 채워진다
- 응답(최상위, 각 섹션 실패 시 null):
  - `btcDominance{current, history[{date, value}]}` — current는 latest(실시간)
  - `altcoinSeason{current, history[{date, value}]}` — 비공식 이력 실패 시 섹션을 null로 하지 않고 `history: []`로 current 유지(이 섹션만의 예외 — 공식/비공식 혼합의 목적), latest 실패 시 null
  - `hashRibbons{current{date, hashrateEh, sma30, sma60, state, buySignal}, history[{...같은 필드}]}` — current는 history 마지막 점(직전 완성일 기준)
  - 세 섹션 history 모두 `days` 적용(`withDays`). `isEmpty`에도 세 섹션 포함
- 캐시: 이력 3종(CMC 도미넌스 이력·비공식 알트 이력·mempool 해시레이트)은 장기 일봉처럼 고정 키에 다음 UTC 자정까지. latest 2종은 메인 10분 캐시 공유. 크레딧 예산: latest 2크레딧 × 144회/일 + 이력 4크레딧/일 ≈ 월 8.8천(15,000 한도 내 — 로컬 개발도 같은 키면 합산되므로 로컬은 별도 키 권장)
- 설정: `cmc.api-key: ${CMC_API_KEY:}`(trading-core `application.yml`, 기본값 빈 문자열 — 필수 환경변수 아님). 키가 비면 CMC 공식 호출은 예외 → 도미넌스·알트 시즌 섹션 null + warn(기동 실패시키지 않는다). 키는 코드·커밋에 넣지 않고 운영 `.env`(`deploy/server/README.md` 목록에 추가)·로컬 `application-local.yml`에만
- 어댑터: CMC는 신규 `stats/adapter/out/coinmarketcap`(Config + Properties + Adapter, `new-adapter` 패턴, 공식·비공식 둘 다 이 어댑터가 담당). mempool 해시레이트는 기존 `MempoolBlockHeightAdapter`에 메서드 추가(포트 개명/분리는 빌더 판단)
- 실패 격리: 세 섹션은 서로·기존 지표와 독립(`guard`). 비공식 API 파싱 실패는 warn 로그로 드러나게
- 세부(빌더 체크 후 내 판단):
  - 이력 조회 길이는 `MAX_DAYS`(365) 고정 — CMC `count=365`, 비공식 `start = 오늘 − 365일`, 응답 `days`는 `withDays`로 자른다. 세 이력 모두 오늘(UTC) 점 제외(timestamp의 UTC 날짜 == 오늘이면 버림)
  - 이력 캐시 실패(예외)는 캐시하지 않는다(장기 일봉 패턴) — 다음 메인 조립(최소 10분, 전부 실패면 1분 뒤)에 재시도. 장애 중 최악 CMC 이력 4크레딧/10분은 한시적이라 감수
  - 키가 비면 어댑터가 HTTP 호출 없이 즉시 예외(크레딧 소모 없음), warn은 조립마다 남아도 무방. 비공식 알트 이력은 latest가 성공했을 때만 호출
  - Hash Ribbons SMA가 null인 일자는 `state: null`, `buySignal: false`. state가 null인 날은 전환으로 보지 않아 대기(pending)를 만들지 않는다(null→RECOVERY는 전환 아님)
  - `CryptoMarketIndicators`에 `halving`처럼 최상위 필드 3개 추가, 응답 필드명은 위 이름 그대로

## 내 판단으로 정한 것 (사용자 확인 전)

- Upbit `KRW-USDT` 시세 노출 방식: `CryptoExchangePort`에 USDT 원화 시세용 메서드를 추가(현재가 + 완성 일봉). 빗썸 등 2차 거래소도 KRW-USDT 마켓이 있어 포트 계약으로 일반화 가능. 내부적으로 `UpbitExchangeAdapter.getCompletedDailyCandles`의 페이징 로직을 마켓 코드 기준으로 재사용
- 펀딩비·OI 대상도 같은 종목 목록(BTCUSDT·ETHUSDT 무기한)
- 엔드포인트 경로: kista-ui `/api/market/**`는 root 소유(공포탐욕)라 trading-core 경로를 그 아래 두지 않는다

## 빌더에게 맡기는 것

- 포트·서비스·DTO 클래스 이름과 세부 분할
- Binance REST 클라이언트 설정(타임아웃 등 — `new-adapter` 패턴)
- 부분 실패 응답 캐시 여부
- 테스트 구성(계산·조인 단위 테스트, 어댑터 MockRestServiceServer류, 컨트롤러 `@WebMvcTest`)

## 산출물 범위 (kista-api)

- 코드 + 테스트
- `docs/agents/modules/tradingcore/stats.md`(신규 어댑터·포트·컨트롤러), `broker.md`(CryptoExchangePort 메서드 추가)
- `src/test/resources/architecture/flows.yml` — 사용자 기능 추가이므로 `flows`·`landscape` 갱신
- `README.md` 드리프트 확인(외부 연동 목록 등)

## UI 핸드오프 (kista-ui 세션용)

- 신규 프록시 라우트 `app/api/crypto-market-indicators/route.ts`, `target: 'trading'`
- `openapi.json` 재생성 → `gen:types`
- 대시보드 `FearGreedSection` 옆 "코인 시장 지표" 영역: 종목별 김프 %(공식 환율·테더 두 기준, 현재 + 200일 추이) + 테더 프리미엄(분해 표시), 펀딩비(현재·다음 정산 + 200일 일평균), OI 30일 추이. 섹션이 `null`이면 해당 카드만 "일시적으로 불러올 수 없음"
- 시장 전체 지표(2026-10-20): 최상위 `btcDominance`·`altcoinSeason`·`hashRibbons` 카드 3개(섹션 null이면 해당 카드만 실패 표시, `altcoinSeason.history`가 빈 배열이면 현재값만). 알트 시즌 0~100(75↑ 알트 시즌·25↓ 비트코인 시즌이 CMC 관례), Hash Ribbons는 `state`·`buySignal` 강조
- 인프라: 운영 `.env`에 `CMC_API_KEY` 추가 필요. 신규 외부 호스트(`api.binance.com`, `fapi.binance.com`, `api.frankfurter.dev`, `mempool.space`, `api.exchange.coinbase.com`, `pro-api.coinmarketcap.com`, `api.coinmarketcap.com`) 외 추가 작업 없음 — egress 제한이 있다면 허용 필요
