# Reapply 쿨다운 정책 설계

## 배경

기존 `reapply()`는 REJECTED 상태인 사용자만 즉시 재신청할 수 있었다.
UI 정책 변경으로 두 가지 새로운 규칙이 적용된다:

- **PENDING 사용자**: 관리자 승인 대기 중 요청이 누락될 경우를 대비해 1시간마다 재신청(알림 재발송) 가능
- **REJECTED 사용자**: 거절 후 24시간이 지나면 재신청 가능

## 상태 전환 규칙

```
REJECTED ---(24h 경과 후)---> PENDING  (reapply)
PENDING  ---(1h 경과 후)----> PENDING  (reapply, 알림 재발송)
```

ACTIVE 등 다른 상태에서 reapply() 호출 시 → 400 Bad Request (기존 동일)

## 데이터 모델 변경

### User record (domain/model/User.java)
`lastReappliedAt: Instant?` 필드 추가 (nullable, 9번째 필드)

역할:
- `reject()` 호출 시 `now`로 설정 → 24시간 카운트다운 시작점
- `reapply()` 성공 시 `now`로 갱신

기존 DB 사용자 처리: `lastReappliedAt = null`이면 쿨다운 없이 즉시 허용 (이미 충분히 대기한 것으로 간주)

### Flyway V12
```sql
ALTER TABLE users ADD COLUMN last_reapplied_at TIMESTAMPTZ;
```

## 비즈니스 로직

### UserService.reapply()
```
1. userId로 User 조회 (없으면 404)
2. status 분기:
   - PENDING: lastReappliedAt != null && now < lastReappliedAt + 1h → CooldownException(retryAfter)
   - REJECTED: lastReappliedAt != null && now < lastReappliedAt + 24h → CooldownException(retryAfter)
   - 그 외: IllegalStateException
3. User를 PENDING + lastReappliedAt=now 로 저장
4. notificationPort.notifyNewUser(updated) 호출 (관리자 알림 재발송)
```

### UserService.reject()
기존 로직에 `lastReappliedAt = now` 추가 — 거절 시점부터 24시간 카운트다운

## 에러 처리

### CooldownException (domain 패키지, Spring 의존 없음)
```java
public class CooldownException extends RuntimeException {
    private final Instant retryAfter;
}
```

### AuthController
- `CooldownException` → **429 Too Many Requests**, body: `retryAfter` ISO-8601
- `IllegalStateException` → 400 Bad Request (기존 동일)

## 테스트 계획

### UserServiceTest 변경
| 기존 | 변경 후 |
|------|---------|
| `reapply_pending_user_throws_exception` | `reapply_pending_within_1h_throws_cooldown` |

### UserServiceTest 신규
- `reapply_pending_after_1h_succeeds` — lastReappliedAt 1시간 초과 → 성공
- `reapply_rejected_within_24h_throws_cooldown` — 24h 미만 → CooldownException
- `reapply_rejected_after_24h_succeeds` — 24h 초과 → 성공
- `reapply_rejected_null_lastReappliedAt_succeeds` — null → 즉시 허용

### AuthControllerTest 신규
- `reapply_cooldown_returns_429` — CooldownException → 429 + retryAfter body

## 수정 파일 목록

| 파일 | 변경 내용 |
|------|----------|
| `domain/model/User.java` | `lastReappliedAt` 필드 추가 |
| `domain/model/CooldownException.java` | 신규 생성 |
| `domain/port/in/ApproveUserUseCase.java` | 주석 업데이트 |
| `application/service/UserService.java` | `reapply()`, `reject()` 로직 변경 |
| `adapter/out/persistence/UserEntity.java` | `lastReappliedAt` 컬럼 추가 |
| `adapter/out/persistence/UserPersistenceAdapter.java` | 필드 매핑 추가 |
| `adapter/in/web/AuthController.java` | CooldownException → 429 처리 추가 |
| `db/migration/V12__add_last_reapplied_at_to_users.sql` | 신규 마이그레이션 |
| `UserServiceTest.java` | 기존 테스트 교체 + 신규 4개 추가 |
| `AuthControllerTest.java` | 429 테스트 추가 |
