# Spring Modulith 점진 도입 설계

## 배경/목적

참고: [MSA도 모놀리스도 아닌 제3의 선택 — Spring Modulith](https://techblog.gccompany.co.kr/msa도-모놀리스도-아닌-제3의-선택-spring-modulith-5f6d1de6399a)

현재 kista-api는 레이어 우선(Hexagonal, `domain/application/adapter` 최상위) 구조로 ArchUnit(`HexagonalArchitectureTest`)이 레이어 의존 방향만 강제한다. 애그리게이트 간(user/account/strategy/trading/finance/notify 등) 경계는 코드 컨벤션으로만 관리되고 강제 검증이 없다.

목적 세 가지:
1. **모듈 경계 강제** — 애그리게이트 간 참조 방향을 컴파일/테스트 시점에 검증
2. **이벤트 기반 결합도 완화** — 모듈 간 신호를 명시적 이벤트로 분리(이번 스코프는 아님, 아래 "보류 항목" 참고)
3. **문서화 자동화** — 모듈 구조 변경 시 다이어그램 자동 갱신

## 전제조건

Spring Boot 4 업그레이드 완료(별도 세션, commit f06d9217 main 병합). Spring Modulith는 Boot 버전에 종속되므로 프레임워크 업그레이드를 선행했다.

## 목표 아키텍처

### 전체 모듈 카탈로그

최상위 패키지를 애그리게이트 기준 모듈로 재편(점진적, 빅뱅 아님):

`finance`(asset 포함), `notify`, `broker`, `kis`, `toss`, `user`, `account`, `strategy`, `trading`, `auth`, `market`, `stats`, `admin`, `privacy`, `settings`

`common/`은 모듈로 승격하지 않고 최상위 비-모듈 공유 유틸로 유지.

### common/(공유 커널) 정책

`common/`(`UsTradeDates`, `AesCryptoService`, `AccountNoHasher` 등)은 Modulith가 공식 지원하는 "모듈 서브패키지 밖 = 검증 제외 대상" 메커니즘을 그대로 활용해 최상위 비-모듈 패키지로 유지한다. DDD의 Shared Kernel 패턴은 특정 소수 모듈이 합의한 도메인 개념에 쓰는 이름이지, 전역 기술 유틸에 붙일 이름이 아니다. 전체를 `shared` 모듈로 승격하는 것은 이번 도입 목적에 맞지 않는다 — 나중에 특정 2~3개 모듈만 공유하는 진짜 도메인 개념이 생기면 그 항목만 개별적으로 승격 검토한다.

### 모듈 내부 구조 템플릿

모듈 내부는 기존 Hexagonal 레이어를 그대로 유지한다. Modulith가 모듈 **외부** 경계를, 기존 ArchUnit 레이어 규칙이 모듈 **내부** 의존 방향을 각각 담당 — 두 규칙은 직교한다.

```
com.kista.finance/
├── domain/
│   ├── model/           ← 불변 값 객체(record)
│   ├── port/in/         ← UseCase 인터페이스(입력 포트)
│   └── port/out/        ← *Port 접미사(출력 포트)
├── application/
│   └── service/         ← UseCase 구현체(package-private @Service)
├── adapter/
│   ├── in/{web,schedule}
│   └── out/persistence
└── package-info.java    ← @ApplicationModule 선언
```

포트 위치는 기존 그대로 **domain 소유**를 유지한다(`domain/port/in`=UseCase 인터페이스, `domain/port/out`=`*Port` 접미사, constraints.md SSOT). 참고 글의 예시(`application/usecase` + `application/port/output`)와는 다른 선택이며, 이 결정의 이유는 "보류 항목" 참고.

## 이전 전략

빅뱅이 아닌 점진적 공존. 애그리게이트 하나씩 모듈로 옮기면서 그 김에 도메인 구성·코드 리팩토링도 함께 진행한다.

**순서**: 의존성 낮은 것부터 — `finance`✅ → `notify`✅ → `broker`/`kis`/`toss` → `trading` 코어. `finance`가 최근 추가돼 매매 코어와 거의 안 얽혀 1번 타깃으로 선정, 여기서 확립한 패턴을 이후 모듈에 확장 적용한다.

**각 모듈은 독립 사이클**: 이 스펙은 원칙(모듈 템플릿·common 정책·포트 위치·테스트 전략)만 정의한다. `finance` 이후 모듈들(notify/broker/kis/toss/trading)의 구체 파일 인벤토리·크로스모듈 의존 분석은 해당 모듈 착수 시점에 별도로 브레인스토밍/계획한다 — 스코프 폭발 방지.

## finance 모듈 상세 (1번 타깃)

### 이동 대상

- `domain/model/finance/*` (17개: AssetSnapshot, FinanceAccount, FinanceBudget, FinanceCategory, FinanceGroup, FinanceTransaction, MonthlyClosing 등 + 각 Command)
- `domain/port/in/{AssetSnapshotUseCase, BulkFinanceRegisterUseCase, MonthlyClosingUseCase, Finance*UseCase}.java` (9개)
- `domain/port/out/{AssetSnapshotPort, MonthlyClosingPort, Finance*Port}.java` (7개)
- `application/service/finance/*` (10개: AssetSnapshotService, BulkFinanceRegisterService, Finance*Service, FinanceRegistrationReminderNotifier, GroupShareSupport, MonthlyClosingService)
- `adapter/in/web/{Finance*, AssetSnapshot*, MonthlyClosing*, AdminFinanceCategory}Controller.java` + 대응 `dto/` (약 26개 DTO)
- `adapter/in/schedule/FinanceRegistrationReminderScheduler.java`
- `adapter/out/persistence/finance/*` (전부 — Entity + JpaRepository + PersistenceAdapter 3종 구성 유지)
- 대응 테스트 파일 전체 (동일 패키지 구조로 이동, 기계적 작업)

`AdminFinanceCategoryController`는 `/api/admin/**` 경로일 뿐 finance 자신의 inbound adapter다 — 별도 admin 모듈의 역참조가 아니므로 finance 모듈 안에 그대로 둔다.

### 크로스모듈 의존 (Named Interface 대상)

`FinanceRegistrationReminderNotifier`가 아직 미이전 상태인 옛 최상위 패키지의 포트를 직접 참조한다:
- `UserPort`, `UserSettingsPort` (user 모듈 소유)
- `UserNotificationPort` (notify 모듈 소유)

이 방향(finance → 나머지, 단방향, 순환 없음)과는 별개로, 반대 방향(레거시 → finance) 참조도 4곳 존재한다 — `UserCascadeDeleter`(탈퇴 cascade가 finance 포트 6개 직접 호출), `MetaController`(`/api/meta`가 finance enum 4종 직렬화), `GlobalExceptionHandler`(finance 중첩 예외 6종 HTTP 매핑), `UserNotificationPort`(범용 알림 포트에 `notifyFinanceRegistrationReminder()` 보유). 넷 다 이번 이전 스코프에서는 손대지 않고 유지한다 — 각각 user/adapter.in.web/notify 모듈이 이전되는 시점에 정리한다. 이 역방향 참조들이 finance를 CLOSED로 선언하면서도 `domain` 레이어를 Named Interface로 공개해야 하는 이유다. user/notify가 아직 모듈로 안 옮겨진 동안은 옛 `com.kista.domain.port.out` 경로 그대로 두고 finance가 참조하도록 허용한다. user/notify 모듈이 이전되면 그 시점 스펙에서 Named Interface로 재정의한다.

### DB

이미 `finance` 스키마로 분리돼 있어(V15 마이그레이션) 추가 DB 변경 불필요.

## 테스트 전략

두 테스트가 직교하는 축을 각각 담당:

1. **`HexagonalArchitectureTest` 일반화** — 기존 `"com.kista.domain.."` 같은 리터럴 최상위 패키지 매처를 `"..domain.."` 식 ArchUnit 관용 와일드카드로 변경. 옛 최상위 구조(`com.kista.domain.*`)와 새 모듈 구조(`com.kista.finance.domain.*`)를 규칙 하나로 동시에 커버 — 이전 기간 내내 규칙을 이중 유지할 필요 없음. 레이어 **방향**(모듈 내부) 검증 담당.
2. **신규 `ModulithArchitectureTest`** — `ApplicationModules.of(KistaApplication.class).verify()` 추가. 모듈 **간** 경계(누가 누굴 참조 가능한지) 검증 담당. 아직 안 옮긴 옛 `common`/`domain`/`application`/`adapter` 최상위 4패키지도 Modulith 입장에선 모듈 후보로 잡히므로, 이전 완료 전까지는 이들에 `@ApplicationModule(type = Type.OPEN)`을 선언해 사실상 개방 — 이전이 끝나 해당 패키지가 비면 package-info와 함께 자연 소멸.

## 보류 항목 (이번 스코프 아님, 별도 작업으로 추후 진행)

1. **포트 위치 전환**: `domain/port/{in,out}` → `application/{usecase,port/output}`. 참고 글 예시와 동일한 형태지만, 이번 마이그레이션 목적(모듈 경계)과 무관한 축이라 함께 하면 diff에 무관한 컨벤션 전면 교체가 얹혀 리뷰·리스크만 커진다. constraints.md "도메인 포트 인터페이스와 타입 위치 규칙" 개정 필요.
2. **이벤트 퍼블리케이션 레지스트리 전면 전환**: 기존 `@TransactionalEventListener` 패턴 전체(사용자 승인/거부/재신청/신규가입, 사이클 종료/신규시작, 매매리포트, 주문취소실패, 사용자탈퇴 등, `application/event/`)를 Modulith Event Publication Registry로 교체. DB에 `event_publication` 테이블 신규 Flyway 필요. 실패 이벤트가 지금은 `log.warn`으로 유실되는데 재시도 가능해지는 효과. finance→notify 같은 신규 모듈간 호출은 이번 스펙에선 이벤트가 아닌 Named Interface 직접 호출로 경계만 긋는다.

## 미해결 확인 필요 항목

없음 — 위 보류 항목 2건은 확인이 아니라 의도적으로 스코프를 뺀 것.
