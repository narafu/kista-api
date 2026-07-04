## Task 2: PRIVACY 기준매매표 조회 중복 제거 — 구현 결과

### 발견한 중복 패턴

`findTodayTrade` 호출 현황 (총 4곳):
1. `ManualTradingService.java:71-73` — `strategy.isPrivacy() ? findTodayTrade(today).orElse(null) : null` ← 교체 대상
2. `TradingPreviewService.java:83-85` — `orderStrategy.requiresPrivacyBase() ? findTodayTrade(today).orElse(null) : null` ← 교체 대상
3. `TradingService.java:92-96` — `hasPrivacy ? findTodayTrade(today).orElse(null) : null` (배치, 유지)
4. `TradingService.java:268-272` — `hasPrivacy ? findTodayTrade(tradeDate).orElse(null) : null` (배치, 유지)

brief에서 "5곳"으로 언급한 것은 `isPrivacy()` 호출 수(5곳)이며,
실제 `findTodayTrade` 조건부 호출 패턴은 4곳. TradingService 배치 2곳은
전략 전체를 1회 DB 조회로 커버하는 구조이므로 별도 메서드 적용 시 오히려 비효율 (변경 안 함).

또한 brief의 `findTodayTrade(strategy.ticker(), today)` 시그니처는 실제 구현과 달랐음.
실제: `findTodayTrade(LocalDate today)` → `Optional<PrivacyTradeBase>`. 이에 맞춰 수정.

### 변경한 파일

1. `src/main/java/com/kista/domain/port/out/PrivacyTradePort.java`
   - `import com.kista.domain.model.strategy.Strategy` 추가
   - `findBaseIfPrivacy(Strategy strategy, LocalDate today)` default 메서드 추가

2. `src/main/java/com/kista/application/service/trading/ManualTradingService.java`
   - 3줄 → 1줄로 단순화

3. `src/main/java/com/kista/application/service/trading/TradingPreviewService.java`
   - 3줄 → 1줄로 단순화 (조건 `requiresPrivacyBase()`은 `strategy.isPrivacy()`와 동치)

### compileJava 결과

BUILD SUCCESSFUL (경고 0)

### 커밋 해시

2efea18c
