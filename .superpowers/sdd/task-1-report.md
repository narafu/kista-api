# Task 1 Report: 스케쥴러 인터럽트 시 락 해제

## 상태: DONE

## 커밋 해시
7600069d (fix(schedule): 인터럽트 시 스케쥴러 락 즉시 해제 — 배포 후 수동 재실행 복구 가능)

## 테스트 결과
- SchedulerJobRunnerTest: 4개 테스트 모두 통과 (인터럽트 rethrow, 예외 시 거짓 완료 알림 제거, 정상 완료)
- TradingCloseSchedulerTest/TradingOpenSchedulerTest: 기존 테스트 포함 18개 모두 통과
- 실행 명령어: `bash gradlew test --tests 'com.kista.adapter.in.schedule.*'` → BUILD SUCCESSFUL
- 전체 테스트: compileJava/compileTestJava 성공, DB 연결 실패로 인한 통합 테스트 실패는 무관

## 구현 내용

### 1. SchedulerJobRunner 수정
- 3-arg `run()` 메서드에 `throws InterruptedException` 선언 추가
- InterruptedException catch 블록에서 `throw e`로 rethrow (기존은 `Thread.currentThread().interrupt()` 후 정상 반환)
- Runnable 오버로드의 "완료" 알림을 try 블록 안으로 이동 (예외 발생 시 완료 알림 미발송)

### 2. 호출측 컴파일 수정
- TradingCloseScheduler.runLocked(): `throws InterruptedException` 추가
- TradingOpenScheduler.runLocked(): `throws InterruptedException` 추가

### 3. 기존 테스트 조정
- TradingCloseSchedulerTest/TradingOpenSchedulerTest의 mock doAnswer에서 InterruptedException 처리 추가
  - anyList() 매처 사용으로 정확한 mock 호출 대응
  - 테스트는 동작 검증 로직 유지 (interrupt flag 확인, 알림 검증)

### 4. 신규 테스트 (SchedulerJobRunnerTest)
- 인터럽트 시 rethrow: assertThatThrownBy로 InterruptedException 검증
- 일반 예외 시 완료 알림 미발송: 예외 발생 후 notifyInfo 호출 안 됨 검증
- Runnable 작업 예외 시 거짓 완료 알림 제거: 예외 발생 후 완료 알림 미발송 검증
- 정상 완료: 시작/완료 알림 모두 발송 검증

## 핵심 버그 수정 효과
- InterruptedException을 rethrow하면 `SchedulerLockService.tryRun()`의 `finally { if (!completed) release(...); }`가 실행됨
- 배포·재시작으로 인터럽트될 때 락이 즉시 해제되므로 관리자 `runNow()` 재실행 불가 상태 (2~3시간 락 잔류) 제거
- Runnable 오버로드의 예외 시 거짓 완료 알림도 함께 수정

## 부작용 없음
- 매매 공식, 주문 생성 로직 변경 없음
- 기존 스케쥴러 테스트 동작 검증 로직 불변
- BOM 체크 완료: 삽입된 BOM 없음
