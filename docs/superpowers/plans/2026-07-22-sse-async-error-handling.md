# SSE Async Error Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 정상적인 SSE 타임아웃과 연결 종료 시 `ProblemDetail` 직렬화와 response-already-committed 연쇄 오류를 방지한다.

**Architecture:** SSE async lifecycle 예외 두 종류를 일반 `ProblemDetail` 매핑에서 분리한다. 전용 `@ExceptionHandler`가 본문 없이 예외를 처리해 이미 커밋된 `text/event-stream` 응답에 다른 미디어 타입을 쓰지 않게 한다.

**Tech Stack:** Java 21, Spring Boot 3.4.4, Spring MVC 6.2, JUnit 5, Mockito, MockMvc, Gradle

## Global Constraints

- 일반 REST 예외의 `ProblemDetail` 계약은 유지한다.
- `AsyncRequestTimeoutException`과 `AsyncRequestNotUsableException`은 app error log에 저장하지 않는다.
- 전용 async lifecycle 핸들러는 응답 본문을 반환하지 않는다.
- Trade SSE 30분 타임아웃과 status SSE 수명 정책은 변경하지 않는다.
- SSE heartbeat와 프론트엔드 재연결 정책은 범위에서 제외한다.
- 신규 코드에는 프로젝트 `//` 주석 규칙을 적용한다.

---

### Task 1: SSE async lifecycle 예외를 본문 없이 처리

**Files:**
- Modify: `src/test/java/com/kista/adapter/in/web/GlobalExceptionHandlerTest.java`
- Create: `src/test/java/com/kista/adapter/in/web/SseAsyncExceptionHandlingTest.java`
- Modify: `src/main/java/com/kista/adapter/in/web/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: Spring MVC `AsyncRequestTimeoutException`, `AsyncRequestNotUsableException`
- Produces: `GlobalExceptionHandler.handleAsyncLifecycle(Exception ex)` with `void` return type

- [ ] **Step 1: 본문 없는 핸들러 실패 테스트 작성**

기존 `asyncRequestNotUsableException_is_not_saved_as_app_error` 테스트가 `handleAll`의 503 `ProblemDetail`을 기대하지 않도록 교체한다. 두 예외 각각에 대해 전용 메서드를 직접 호출하고 app error log에 상호작용이 없음을 검증한다.

```java
@Test
void asyncRequestNotUsableException_is_handled_without_response_body() {
    AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
    GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);

    handler.handleAsyncLifecycle(
            new AsyncRequestNotUsableException("ServletOutputStream failed to flush"));

    verifyNoInteractions(appErrorLogPort);
}

@Test
void asyncRequestTimeoutException_is_handled_without_response_body() {
    AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
    GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);

    handler.handleAsyncLifecycle(new AsyncRequestTimeoutException());

    verifyNoInteractions(appErrorLogPort);
}
```

- [ ] **Step 2: 테스트를 실행해 RED 확인**

Run: `./gradlew test --tests 'com.kista.adapter.in.web.GlobalExceptionHandlerTest'`

Expected: `handleAsyncLifecycle(Exception)`가 없어 테스트 컴파일 실패.

- [ ] **Step 3: 본문 없는 전용 핸들러 최소 구현**

두 async 예외를 `MAPPINGS`에서 제거하고 다음 메서드를 `GlobalExceptionHandler`에 추가한다.

```java
// SSE 타임아웃·연결 종료는 이미 끝난 스트림에 별도 응답 본문을 쓰지 않고 종료 처리
@ExceptionHandler({AsyncRequestTimeoutException.class, AsyncRequestNotUsableException.class})
public void handleAsyncLifecycle(Exception ex) {
    log.debug("SSE async request 종료: {}", ex.getClass().getSimpleName());
}
```

`void` 반환을 유지하고 `ProblemDetail`, `ResponseEntity`, `@ResponseStatus`를 추가하지 않는다.

- [ ] **Step 4: 핸들러 테스트 GREEN 확인**

Run: `./gradlew test --tests 'com.kista.adapter.in.web.GlobalExceptionHandlerTest'`

Expected: 모든 테스트 통과.

- [ ] **Step 5: SSE 관련 회귀 테스트 실행**

`SseAsyncExceptionHandlingTest`는 `MockMvcBuilders.standaloneSetup`으로 테스트용 SSE 컨트롤러와 실제 `GlobalExceptionHandler`를 구성한다. 테스트 컨트롤러가 반환한 `SseEmitter`를 `completeWithError(new AsyncRequestTimeoutException())`와 `completeWithError(new AsyncRequestNotUsableException("disconnected"))`로 각각 완료한 뒤 `asyncDispatch`한다. 두 dispatch 모두 `HttpMessageNotWritableException` 없이 완료되고 `AppErrorLogPort`에 상호작용이 없음을 검증한다. 테스트 컨트롤러는 `@GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)`이며 연결 직후 ping 이벤트를 보내 커밋된 SSE 경로를 재현한다.

Run: `./gradlew test --tests 'com.kista.adapter.in.web.SseAsyncExceptionHandlingTest'`

Expected: 두 async dispatch 모두 예외 핸들러의 본문 직렬화 실패 없이 통과.

- [ ] **Step 6: 기존 SSE 관련 회귀 테스트 실행**

Run: `./gradlew test --tests 'com.kista.adapter.in.web.TradeStreamControllerTest' --tests 'com.kista.adapter.out.sse.*' --tests 'com.kista.adapter.in.web.AuthControllerTest'`

Expected: 기존 SSE 연결, ping, 이벤트 전송, 인증 흐름 테스트 모두 통과.

- [ ] **Step 7: 컴파일과 diff 검증**

Run: `./gradlew compileJava && git diff --check`

Expected: `BUILD SUCCESSFUL`, whitespace 오류 없음.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/web/GlobalExceptionHandler.java \
        src/test/java/com/kista/adapter/in/web/GlobalExceptionHandlerTest.java \
        src/test/java/com/kista/adapter/in/web/SseAsyncExceptionHandlingTest.java
git commit -m "fix(sse): async 종료 예외를 본문 없이 처리"
```
