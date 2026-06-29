### Task 1 구현 리포트

**구현 내용 요약**

1. `Strategy`에서 `divisionCount`를 제거하고 공통 필드만 유지하도록 정리했다.
2. `StrategyInfiniteDetail`, `CyclePositionInfiniteDetail`를 추가해 INFINITE 전용 필드를 분리했다.
3. `CyclePosition`에서 `isReverseMode`를 제거하고 `StrategyCycle`에서 `seedResolvedBy`를 제거해 공통 모델을 축소했다.
4. `StrategyDetail`에 `divisionCount`를 추가하고, 소유 범위의 테스트 픽스처를 새 시그니처에 맞게 수정했다.
5. brief에 지정된 red 테스트 `toDetail_infiniteStrategy_returnsDivisionCountFromDetail`를 추가했다.

**테스트 결과**

- Red 확인: `./gradlew test --tests 'com.kista.application.service.strategy.StrategyServiceTest'`
  - `:compileTestJava` 실패
  - 원인: `Strategy` 생성자 인자 수 불일치, `StrategyInfiniteDetail` 부재, `StrategyDetail.divisionCount()` 부재
- Task 범위 검증: `./gradlew test --tests 'com.kista.application.service.strategy.StrategyServiceTest' --tests 'com.kista.application.service.trading.CycleRotationServiceTest' --tests 'com.kista.application.service.trading.TradingServiceTest'`
  - `:compileJava` 실패
  - 정확한 실패 지점:
    - `src/main/java/com/kista/adapter/out/persistence/strategy/StrategyCycleEntity.java`
    - `src/main/java/com/kista/application/service/trading/SeedResolutionPolicy.java`
    - `build/generated/querydsl/com/kista/adapter/out/persistence/strategy/QStrategyCycleEntity.java`
  - 정확한 실패 원인: out-of-scope 파일들이 여전히 `StrategyCycle.SeedResolvedBy`를 참조함

**범위 및 우려 사항**

1. brief와 사용자 지시에 따라 persistence, 서비스 본체, 컨트롤러, 기타 테스트는 수정하지 않았다.
2. 현재 빌드 실패는 Task 1에서 제거한 `StrategyCycle.SeedResolvedBy`를 후속 작업이 아직 정리하지 않아 발생한 중간 상태다.
3. owned 파일 기준으로는 공통/INFINITE 상세 분리 시그니처를 반영했다.

### Task 1 fix pass 리포트

**수정 내용 요약**

1. `StrategyCycle.SeedResolvedBy` 제거 후 남아 있던 즉시 컴파일 오류를 `StrategyCycleEntity`, `StrategyCyclePersistenceAdapter`, `SeedResolutionPolicy`에서 정리했다.
2. stale 주석을 `cycle_position.is_reverse_mode` 기준 문구에서 공통 모델/상세 모델 분리 문구로 교체했다.
3. 전환 단계 호환을 위해 `Strategy`/`CyclePosition` 공통 모델에는 기본 접근자만 남기고, `TradingCycleResponse`가 `StrategyDetail.divisionCount()`를 우선 사용하도록 수정했다.
4. `StrategyServiceTest`의 약한 record 단언을 `TradingCycleResponse` 매핑 검증으로 강화했고, `register()` 성공 케이스가 요청한 `divisionCount`를 응답 detail에 반영하는지 확인하도록 보강했다.

**검증 결과**

- `./gradlew compileJava`
  - 성공
- `./gradlew test --tests 'com.kista.application.service.strategy.StrategyServiceTest' --tests 'com.kista.application.service.trading.CycleRotationServiceTest' --tests 'com.kista.application.service.trading.TradingServiceTest'`
  - `:compileTestJava` 실패
  - 실패 파일은 이번 fix 소유 범위 밖 테스트들:
    - `src/test/java/com/kista/adapter/in/schedule/TradingOpenSchedulerTest.java`
    - `src/test/java/com/kista/adapter/in/schedule/TradingCloseSchedulerTest.java`
    - `src/test/java/com/kista/adapter/in/web/TradingCycleControllerTest.java`
    - `src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java`
    - `src/test/java/com/kista/application/service/trading/OrderCancelServiceTest.java`
    - `src/test/java/com/kista/application/service/trading/ManualTradingServiceTest.java`
  - 공통 실패 원인: 테스트 픽스처가 여전히 제거된 `Strategy` 7-arg 생성자, `CyclePosition` 9-arg 생성자, `StrategyCycle.SeedResolvedBy`, 구 `StrategyDetail` 시그니처를 사용함

**우려 사항**

1. 이번 fix는 Task 1 범위만 유지하기 위해 상세 퍼시스턴스 테이블이나 비소유 테스트까지는 확장하지 않았다.
2. 따라서 프로덕션 `compileJava`는 복구됐지만, focused test 실행은 위 비소유 테스트 픽스처 정리 전까지 계속 막힌다.
