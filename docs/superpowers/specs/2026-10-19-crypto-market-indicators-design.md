# 코인 시장 지표(김프·펀딩비·OI) 대시보드 API — 설계

## 의도

코인 DCA 사용자가 대시보드에서 "지금 코인 시장이 과열인가"를 한눈에 판단하도록, 국내 현물 과열(김치 프리미엄)과 글로벌 레버리지 과열(선물 펀딩비·미결제약정)을 공포탐욕지수 카드 옆에 보여준다. 이 문서는 kista-api(trading-core) 범위만 다룬다 — kista-ui 카드·차트, 인프라 작업은 각 레포 세션에서 별도로 진행한다(아래 "UI 핸드오프").

## 목표

- 코인 DCA 지원 종목 전체(현재 BTC·ETH — `StrategyTicker` 중 `Market.CRYPTO_KRW`)에 대해 세 지표의 현재값 + 일 단위 추이를 한 엔드포인트로 제공
  - 김치 프리미엄: 현재값 + 최근 N일(기본·최대 200) 일별 추이
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

- 외부 호출 가능성 실측 완료(2026-10-19, OCI 춘천 `kista-server`에서 모두 HTTP 200): `fapi.binance.com/fapi/v1/ping`, `/fapi/v1/fundingRate`, `/futures/data/openInterestHist?period=1d&limit=30`, `api.binance.com/api/v3/klines`, `api.upbit.com/v1/ticker?markets=KRW-USDT`
- 모든 호출은 키 없는 공개 API
- kista-trading은 단일 인스턴스 — JVM 메모리 캐시로 충분(Redis 불필요)
- Binance `openInterestHist`는 최근 30일만 제공 → OI 추이는 30일 고정
- 프로젝트 규칙: `docs/agents/constraints.md`(주석·네이밍·REST·ProblemDetail), REST 클라이언트는 `new-adapter` skill 3파일 패턴

## 결정된 사항

### 배치 (trading-core)
- 엔드포인트·서비스는 `com.kista.tradingcore.stats`(조회 전용 소비자). kista-ui가 이미 trading-core(8081)를 직접 프록시하고, Upbit 종목 매핑(`UpbitMarketRegistry`)·공개 시세 호출이 trading-core broker에 있어 "DCA 지원 종목 = 지표 대상 종목"이 같은 기준을 따른다
- Binance 공개 시세 어댑터는 `stats/adapter/out/binance`(선례: `stats/adapter/out/alpaca/AlpacaCandleAdapter` — stats 소유 출력 포트를 stats 어댑터가 구현). Binance 심볼은 `ticker.name() + "USDT"`(BTCUSDT·ETHUSDT) — 별도 레지스트리 없음
- Upbit 시세는 broker `CryptoExchanges.of(Broker.UPBIT)`의 `CryptoExchangePort` 경유(broker `adapter/out`은 모듈 밖 비공개). 코인 KRW 현재가·완성 일봉은 기존 `getCurrentPrices`·`getCompletedDailyCandles` 재사용

### 계산식
- 김치 프리미엄(%) = (Upbit `KRW-<코인>` ÷ (Binance `<코인>USDT` × Upbit `KRW-USDT`) − 1) × 100
  - 환율 API 대신 Upbit `KRW-USDT`를 쓴다 — 테더 프리미엄을 뺀, 앱 사용자가 체감하는 값
  - 현재값: 세 현재가(Upbit 코인·Upbit USDT·Binance 현물)
  - 추이: 일봉 종가 기준, **UTC 날짜 키로 조인**(인덱스 정렬 금지). Upbit 일봉은 KST 09:00(=UTC 00:00) 시작이라 `CryptoCandle.kstDate`가 곧 UTC 날짜, Binance kline `openTime`의 UTC 날짜와 일치. Binance kline은 진행 중 봉을 포함하지만 Upbit 쪽이 완성 봉만 주므로 세 시계열의 날짜 교집합만 쓰면 자동 제외
- 펀딩비: 현재값 = `/fapi/v1/premiumIndex`의 `lastFundingRate`(+`nextFundingTime`), 추이 = `/fapi/v1/fundingRate`(8시간 간격) 레코드를 `fundingTime`의 UTC 날짜로 묶은 일평균. 비율 원값(예: 0.0001) 그대로 반환 — % 표기는 UI 몫
- OI: `/futures/data/openInterestHist?period=1d&limit=30`의 `sumOpenInterestValue`(USD 명목가) 일별 추이. 별도 현재값 호출 없음 — 최신 포인트가 현재값 역할

### API
- `GET /api/crypto-market-indicators?days=200` (인증 사용자, 기본 200, 범위 1~200 — 벗어나면 400). `days`는 김프·펀딩비 추이에만 적용, OI는 항상 최근 30일
- 응답(전용 Response DTO, 섹션별 nullable):
  - `asOf`: 계산 시각
  - `tickers[]`: `ticker`, `kimchiPremium{current, history[{date, value}]}`, `fundingRate{current, nextFundingTime, history[{date, value}]}`, `openInterest{history[{date, valueUsd}]}`
- 실패 격리: 종목·지표 단위로 실패를 잡아 해당 섹션만 `null` + warn 로그(`FearGreedService` 소스별 try/catch 패턴). 모든 종목의 모든 섹션이 실패하면 503(기존 매핑되는 예외 재사용 — 빌더 판단)
- `Cache-Control` 공개 캐시 10분(`FearGreedController` 패턴)

### 캐시
- 서비스 내 JVM 메모리 캐시, TTL 10분. 캐시 단위는 최대 범위(200일) 원본 계산 결과 1벌이고 `days`는 캐시된 추이를 잘라서 응답한다 — `days` 값마다 외부 호출이 늘지 않도록(내 판단). 실패한 섹션이 섞인 응답도 캐시할지는 빌더 판단이되, 전체 실패(503)는 캐시하지 않는다 (선례: `TossMarketCalendarCache` — 예외·empty 미캐싱)

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
- 대시보드 `FearGreedSection` 옆 "코인 시장 지표" 영역: 종목별 김프 %(현재 + 200일 추이), 펀딩비(현재·다음 정산 + 200일 일평균), OI 30일 추이. 섹션이 `null`이면 해당 카드만 "일시적으로 불러올 수 없음"
- 인프라: 신규 외부 호스트(`api.binance.com`, `fapi.binance.com`) 외 추가 작업 없음 — egress 제한이 있다면 허용 필요
