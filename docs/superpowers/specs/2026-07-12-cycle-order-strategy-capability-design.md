# CycleOrderStrategy Capability 확장 + KIS/Toss prevClose 캐싱 — 설계 문서

## Context

트랙 B 백로그(3차 사이클부터 이월) 중 kista-api 쪽 2개 항목을 다룬다. 조사 단계에서 원래 백로그의 전제가 틀렸음을 확인했다:

1. **"CycleOrderStrategy를 persistence/execution 레이어까지 확장"** — 당초 사용자가 "포트 역전"(domain에 새 포트 정의 + application이 구현)으로 승인했으나, 코드 재확인 결과 이 방식은 불필요하다는 것을 발견해 사용자에게 재확인 후 더 단순한 "capability 메서드 확장"으로 방향을 바꿨다(브레인스토밍에서 승인됨). `CyclePositionPersistor`/`TradingOrderExecutor`의 타입 분기(`isInfinite()`/`isPrivacy()`/`isVr()`)는 "이 부수효과를 실행할지" 여부만 묻는 것이라, 순수 값을 반환하는 capability 메서드(기존 `endsCycleOnLiquidation()`과 동일 패턴)로 대체 가능하다. 이러면 domain→application 의존이 생기지 않고 10개 테스트 파일의 생성자도 무변경이다.
2. **"TradingPriceFetcher 가격 조회 캐싱"** — 당초 제목과 달리 `TradingService`/`TradingPriceFetcher` 계층에는 중복 조회가 없음을 확인했다(배치당 1회). 실제 비효율은 한 단계 아래 `KisPriceApi`/`TossPriceApi`의 전일종가(prevClose) 조회가 종목마다 개별 API 호출을 하는 구조에 있다. 확인된 장애/레이트리밋 사례는 없지만, 사용자가 예방적으로 캐싱 구현을 승인했다.

## 확정된 결정 사항

### 1. CycleOrderStrategy capability 메서드 3개 추가 (CycleState sealed interface화는 하지 않음)

`CycleState`를 sealed interface로 나누는 안은 기각한다 — 소비부(`placeAll`)에 새 `switch`/`instanceof` 분기가 생길 뿐 타입 분기 자체가 사라지지 않고, 오히려 "필드를 그대로 넘기고 받는 쪽이 null 체크"하는 현재의 단순함을 깨뜨린다.

대신 `domain/strategy/CycleOrderStrategy.java`에 다음 3개 메서드를 추가한다(전부 default 구현 제공, 필요한 구현체만 override — 기존 `endsCycleOnLiquidation()` 패턴과 동일):

```java
default boolean tracksReverseMode() { return false; }       // INFINITE만 true
default boolean requiresRolloverCheck() { return false; }   // VR만 true
default PriceCapMode priceCapMode() { return PriceCapMode.NONE; } // INFINITE=INFINITE_POSITION, PRIVACY=PRIVACY_SIMPLE
```

`PriceCapMode`는 `CycleOrderStrategy` 인터페이스 내부에 nested enum으로 정의: `enum PriceCapMode { NONE, INFINITE_POSITION, PRIVACY_SIMPLE }` (import 시 `CycleOrderStrategy.PriceCapMode`).

### 2. 적용 지점

- `CyclePositionPersistor.java`: `strategy.isInfinite()`(리버스모드 detail 저장 여부) → `cycleStrategies.of(strategy).tracksReverseMode()`. `strategy.isVr()`(rollIfDue 호출 여부) → `cycleStrategies.of(strategy).requiresRolloverCheck()`.
- `TradingOrderExecutor.java`: `position != null` / `strategy.isPrivacy()` 가격 캡 분기 → `cycleStrategies.of(strategy).priceCapMode()`를 switch해서 `capIfNeeded`/`capPrivacyIfNeeded`/no-op 중 선택.
- `endsCycleOnLiquidation()`은 이미 같은 패턴으로 존재하므로 변경 없음 — 이번 확장이 기존 패턴과 일관됨을 보여주는 근거로 유지.

### 3. KIS/Toss prevClose 캐싱

`adapter/out/broker/`에 신규 `PrevCloseCache` 클래스(public final, `DoubleCheckedTokenCache`와 동일하게 각 어댑터가 `private final` 필드로 자체 인스턴스 소유 — Spring bean 아님)를 추가한다.

```java
public final class PrevCloseCache {
    private final ConcurrentMap<CacheKey, Optional<BigDecimal>> cache = new ConcurrentHashMap<>();
    public record CacheKey(String symbol, LocalDate date) {}

    public Optional<BigDecimal> getOrFetch(String symbol, LocalDate date, Supplier<Optional<BigDecimal>> fetcher) {
        return cache.computeIfAbsent(new CacheKey(symbol, date), k -> fetcher.get());
    }
}
```

`KisPriceApi.fetchLatestClose(ticker, excd, account)`와 `TossPriceApi.fetchPrevClose(symbol, fallback)` 내부 로직을 이 캐시로 감싼다. 키는 `(symbol, LocalDate.now(TimeZones.KST))` — 같은 KST 날짜 안에서는 전일종가가 불변이므로 안전. 만료/eviction 로직은 두지 않는다(티커 종류가 4개뿐이라 메모리 증가 무시 가능, 날짜가 바뀌면 키가 자연히 달라짐).

## 에러 처리

- `PrevCloseCache.getOrFetch`의 `fetcher`가 예외를 던지면 `computeIfAbsent`가 캐시에 남기지 않고 그대로 예외 전파 — 기존 `fetchLatestClose`/`fetchPrevClose`의 try/catch(로그 후 `Optional.empty()`/`fallback` 반환)가 fetcher 람다 내부에 그대로 유지되므로 캐시 계층은 예외 처리에 관여하지 않는다.

## 테스트

- `CycleOrderStrategy` capability 메서드: `InfiniteCycleOrderStrategy`/`PrivacyCycleOrderStrategy`/`VrCycleOrderStrategy` 각각의 override 값을 검증하는 단위 테스트 추가(기존 `CycleOrderStrategyCapabilityTest` 패턴 재사용).
- `CyclePositionPersistor`/`TradingOrderExecutor`: 기존 테스트가 `strategy.isInfinite()` 등에 의존한 목(mock) 설정을 하지 않으므로(실제 Strategy 객체의 type 필드로 판단), capability 메서드 교체 후에도 기존 테스트가 그대로 통과해야 한다 — 회귀 테스트로 확인.
- `PrevCloseCache`: 같은 (symbol, date) 키로 2회 호출 시 fetcher가 1회만 호출되는지 확인하는 단위 테스트.
- `KisPriceApi`/`TossPriceApi`: 같은 배치 실행 내 동일 종목 재조회 시 HTTP 호출이 1회만 발생하는지 확인(기존 테스트에 캐시 히트 검증 케이스 추가).

## 범위 밖

- `CycleState` sealed interface화 — 명시적으로 기각.
- 현재가(current price) 캐싱 — 실시간성 필수라 캐싱 대상 아님, prevClose만 대상.
- KIS/Toss 실제 rate limit 스펙 확인 — 근거 문서 없음, 확인된 장애 없이 예방적으로만 진행.
