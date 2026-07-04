## Task 1: UserSettings 로드 + 기본값 fallback 중복 제거

### 발견한 중복 패턴 수
5개 사용처에서 동일 패턴 반복:
```java
userSettingsPort.loadByUserId(userId).orElse(UserSettings.defaultFor(userId))
```

### 변경한 파일 목록
1. `src/main/java/com/kista/domain/port/out/UserSettingsPort.java` — `findOrDefault(UUID)` default 메서드 추가
2. `src/main/java/com/kista/application/service/user/UserSettingsService.java` — `findOrDefault` 사용으로 교체
3. `src/main/java/com/kista/application/service/trading/TradingReporter.java` — `findOrDefault` 사용으로 교체
4. `src/main/java/com/kista/application/service/trading/CycleRotationService.java` — `findOrDefault` 사용으로 교체
5. `src/main/java/com/kista/application/service/trading/TradingService.java` — `findOrDefault` 사용으로 교체
6. `src/main/java/com/kista/application/service/strategy/StrategyService.java` — `findOrDefault` 사용으로 교체

### 특이사항
- 포트 메서드명이 brief의 `findByUserId` 가 아닌 `loadByUserId` 임 — default 메서드 내부에서 `loadByUserId` 호출로 구현
- `orElse` → `orElseGet` 으로 변경 (값이 있을 때 불필요한 `defaultFor` 객체 생성 방지)

### compileJava 결과
BUILD SUCCESSFUL
