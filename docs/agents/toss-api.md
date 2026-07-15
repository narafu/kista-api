## 토스증권 API

**공식 문서**: https://developers.tossinvest.com/docs

응답 구조·파라미터 명세·오류 코드는 공식 문서가 SSOT. 아래는 kista 코드베이스에 특화된 패턴과 주의사항만 기록.

### 어댑터 구조

- `TossHttpClient` — 공통 헤더 처리 (package-private), `TossConfig`에서 RestTemplate 빈 주입
- API 클래스 6개: `TossAuthApi`, `TossCandleApi`, `TossHoldingsApi`, `TossOrderApi`, `TossPriceApi`, `TossMarketApi`
- `TossBrokerAdapter`: `BrokerAdapterPort` + 공통 5개 Port + Toss 전용 5개 Port 구현
  - Toss 전용: `CandlePort`, `ExchangeRatePort`, `StockInfoPort`, `MarketCalendarPort`, `BrokerAccountPort`

### 계좌번호 포맷

- `XXX-XX-XXXXXX` (하이픈 포함) — KIS `XXXXXXXX-XX`와 다름, `AccountInfoStep` 분기 처리 필요

### 날짜 처리 (KIS와 다름)

- `TossOrderApi.fetchExecutions()`: Toss는 **주문 접수일(KST)** 기준 날짜 필터링 — `toUtc()` 변환 없이 KST 날짜 전달
- **`queryFrom = from - 1일`**: 전날 저녁 선접수 주문이 당일 장마감에 체결될 수 있어 1일 앞당겨 조회 후, `filledAt(KST)` 기반 필터링
- KIS(`toUtc()` 변환 필수)와 반대 방향 — 혼용 금지

### 주의사항

- KIS API와 필드명·인증 방식이 다르므로 혼용 주의
- `TossApiException` → `GlobalExceptionHandler` 503 자동 처리
- `Account.isToss()` 삭제됨 — `account.broker() == Account.Broker.TOSS` 직접 비교

### 전일종가(prevClose) 조회 — TossPriceApi.fetchPrevClose()

- `/api/v1/prices`에는 전일종가 필드가 없음(현재가만 제공) — 반드시 `TossCandleApi.getCandleBefore(symbol, "1d", before)`(캔들 `count=1`)로 별도 조회
- `before` 시각 계산이 핵심: `DstInfo.isRegularSessionActive()`가 true(정규장 진행 중)면 그 세션의 봉이 아직 미확정이므로 `DstInfo.lastSessionOpenInstant().minusMillis(1)`을 `before`로 사용해 배제, false면 이미 확정된 봉만 있으므로 `Instant.now()`
- **`DstInfo.marketOpen` 필드를 그대로 쓰면 안 됨**: 이 필드는 "오늘 날짜" 고정이라 자정~개장 전(00:00~22:30 KST) 사이 호출 시 실제 진행 중인 세션(전날 저녁 개장)이 아닌 미래 시각을 가리킴 — `lastSessionOpenInstant()`가 `nextTradeDate()`와 같은 패턴으로 날짜 롤백 처리한 버전이므로 이걸 사용
- `PrevCloseCache` 키에 세션 버킷(`"ACTIVE"`/`"CLOSED"`) 포함 — 정규장 종료로 확정 종가가 바뀌는 순간 같은 KST 날짜 내에서도 캐시를 재사용하지 않고 재조회
- 과거 이력: 최신 캔들 2개 중 배열 인덱스로 확정 종가를 추측하던 방식이 "장마감 후에도 최신 캔들을 미확정으로 오인" + "DIRECT가 프리마켓까지 포함해 그 구간도 stale" 버그 2건을 냈음 — `count=1`+`before` 방식으로 인덱스 추측 자체를 제거해 해결
