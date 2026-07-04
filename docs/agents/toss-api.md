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
