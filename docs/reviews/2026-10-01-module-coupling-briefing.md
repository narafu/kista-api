# kista-api 모듈 결합도 재검토 — 결과 브리핑 (2026-10-01)

2026-09-30 전면 리뷰(`2026-09-30-module-coupling-review.md`, 결함 F1~F10)에서 시작한 7단계 로드맵과 후속 2회의 최종 결과를 한 장으로 정리한다. 단계별 상세 경위는 `docs/agents/modulith-migration-history.md`의 "결합도 재검토 N단계" 절, 배포 주의사항은 `2026-09-30-module-coupling-handoff.md` §5가 SSOT다. 수치는 2026-10-01 코드 기준 실측값이다.

진행 방식: 단계마다 구현 → 전체 컴파일·테스트 → 리뷰어 검수(`/code-review`, 보안 민감 단계는 high) → 커밋 → main 배포(헬스게이트). 되돌아가지 못하도록 단계마다 ArchUnit 잠금 규칙을 추가했다.

## 1. 단계별 작업 내용

| 단계 | 해결한 결함 | 핵심 변경 | 잠금 규칙 |
|---|---|---|---|
| 1 | F1 프로세스 간 양방향 HTTP | 런타임 정책(brokers·strategies)을 trading-core 소유 `trading.trading_runtime_settings`로 이동, 오류 로그를 동기 HTTP(`POST /api/internal/errors`)에서 Redis Stream(`stream:app.error`) push로 전환, `sharedkernel.port` 폐지 | `GradleModuleBoundaryTest.tradingCoreMustNotCallRootInternalApi` |
| 2 | F2 Published Language 부재 | `com.kista.contract`(`:shared`) 신설로 own-type 복제 20쌍 제거, 내부 컨트롤러 11곳의 도메인 record 직접 직렬화를 contract DTO 매핑으로 교체, `OwnTypeContractTest` 폐지 | `InternalApiContractTest`, `contract_must_not_depend_on_other_modules` |
| 3 | F3 trading 신(god) 모듈 | `tradingstats`·`tradingnotify` 분리, `BrokerApiException`(벤더 중립 상위 예외)·`BrokerStatisticsPort`(벤더 중립 통계 포트)로 Toss/KIS 타입 침투 차단 | `vendor_models_must_not_leak_outside_broker` |
| 4 | F4·F5 커널 순수성, 상수를 HTTP로 조회 | matching 커널 입력을 커널 소유 `PrivacyPlan`으로, `StrategyCapability`를 sharedkernel 상수 SSOT로 승격해 `/api/meta`의 trading-core HTTP 호출 제거 | `matching_must_not_depend_on_other_modules`(허용 목록 방식), `inbound_adapters_must_not_perform_outbound_http` |
| 5 | F6 notify 정체성 | 텔레그램 봇 명령 채널(승인/거절/조회)을 notify → admin 이관, `UserNotificationPort`가 `User` 대신 notify 소유 `NotificationRecipient`를 받음, Telegram/Alpaca/Blacklist 인프라 중복을 `platform` 1벌로 | `notify_must_stay_pure_outbound_gateway` |
| 6 | F7 앱셸 비대칭·숨은 결합 | `com.kista.tradingweb` 앱셸 신설(부팅 진입점·전역 advice·JPA 배선), 라우트 인가 정책을 셸이 기여하는 `SecurityRoutePolicy`, `ProblemDetailMappings` 공용화, `ErrorLogAspect` 삭제 후 `AppErrorRaisedEvent`로 오류 기록 경로 단일화(`REQUIRES_NEW` 저장) | `no_aspects_in_codebase`, `web_must_stay_pure_inbound_sink`(web+tradingweb), `SecurityConfigOrderingTest` |
| 7 | F8~F10 | `BatchContext`에서 `Account` 제거(`TradingAccount` 투영, `BrokerAccountRef.toString` 마스킹), finance 리마인더 이벤트화(`FinanceRegistrationReminderDueEvent`), 체결 FCM 푸시 Pub/Sub → Stream(MAXLEN 10,000), `stats→benchmark`·`AdminUserView→UserSummary` 개명, 역할 변경 토큰 무효화를 `UserUseCase.changeRole`로 | `trading_batch_path_must_not_carry_account_aggregate`(denylist) |
| 후속 1 | §6 잔여 | Legacy Pub/Sub 구독자 제거(Stream 전환 완료), trading-core user 이벤트 컨슈머 2개를 `RedisStreamSubscriber` 베이스로 이관 — 실패 정책 훅(`ackOnFailure`/`maxDeliveries`): 멱등 cascade는 XCLAIM 재시도 최대 24회, last-write upsert·유실 허용 소비자는 ack | — |
| 후속 2 | §6 잔여 | `UserChannelNotifier`로 유형 무관 채널 라우팅 공용화, finance → user를 `FinanceMemberPort` 어댑터 1곳으로 한정, tradingstats 환율 소스를 유스케이스에서 `Broker.TOSS`로 명시(포트 직접 주입 제거) | `TradingCoreTestVendorModelTest`(trading-core 테스트 소스도 벤더 타입 금지) |

배포: 실행 #105(1~7단계), #106(후속 1), #107(후속 2) 모두 Full Test Suite(실제 Postgres·Redis) 통과 후 kista-api·kista-scheduler·kista-trading 세 role의 헬스게이트를 통과했다. 롤백 없음.

## 2. 구조적으로 개선된 점

리뷰 문서 §6의 목표 수치 대비 실측 결과.

| 지표 | 이전(2026-09-30 리뷰 시점) | 현재(2026-10-01) |
|---|---|---|
| 프로세스 간 동기 HTTP 방향 | 양방향 (root→trading 33, trading→root 3) | root → trading-core 단방향 (역방향 0) |
| own-type 복제 쌍 | 20 (+사설 3) | 0 |
| 내부 컨트롤러의 도메인 record 직접 반환 | 11 메서드 | 0 |
| Modulith 모듈 수 | 15 | 19 |
| `trading` 모듈 파일 수 | 261 | 189 |
| trading 모듈의 `Account` import | 29 파일 | 11 파일 (소유권 검증 요청 경로만) |
| `matching` outbound 모듈 | sharedkernel + privacy | sharedkernel만 |
| 컨트롤러·advice 안의 RestClient | 2 | 0 |
| AOP 기반 모듈 간 결합 | 1 | 0 |
| 인프라 코드 중복 (Telegram/Alpaca/Blacklist/Stream 컨슈머) | 2~3벌 | 각 1벌 |
| `finance → notify` / `finance → user` | 1 / 5 | 0 / 어댑터 1파일 |
| ArchUnit 규칙 (root `HexagonalArchitectureTest`) | 18 | 25 (+ trading-core 테스트용 1) |

설계 관점의 변화 다섯 가지.

- **프로세스 토폴로지에 방향이 생겼다.** 이전엔 어느 프로세스도 leaf가 아니어서 한쪽 장애가 반대쪽 기능을 무너뜨렸다. 지금은 trading-core가 leaf이고, trading-core → root 방향 전달은 전부 Redis Stream(내구성 push)이다. root가 내려가도 매매 프로세스의 계좌·전략 등록과 오류 보고는 실패하지 않는다.
- **계약이 컴파일 타임에 검증된다.** 내부 API의 wire 타입은 `com.kista.contract` 한 곳에 선언돼 양쪽이 같은 클래스를 쓴다. 리플렉션으로 shape를 비교하던 테스트는 컴파일러가 대신한다. 도메인 필드 추가가 조용히 wire를 바꾸는 일이 없다.
- **모듈 경계가 Modulith가 볼 수 있는 형태로만 존재한다.** 문자열 포인트컷 AOP, 서비스 로케이터, 인프라 leaf에 박힌 프로세스 고유 라우트처럼 정적 분석 사각지대였던 결합을 이벤트·포트·셸 기여 방식으로 바꿨다.
- **각 모듈이 이름값을 한다.** notify는 순수 아웃바운드 게이트웨이, matching은 순수 계산 커널, web/tradingweb은 대칭 앱셸, benchmark는 tradingstats와 구분되는 이름이다. 배치 경로는 자격증명이 든 `Account` 대신 브로커 호출용 투영만 들고 다닌다.
- **횡단 인프라가 platform 한 벌로 수렴했다.** Redis Stream 구독(재기동·XCLAIM 복구·실패 정책), 텔레그램 HTTP, 예외→ProblemDetail 매핑, 라우트 인가 순서가 각각 단일 구현이고, 소비자는 정책(재시도 여부, 기여 라우트)만 선택한다.

## 3. 향후 과제와 리스크

인계 메모 §6의 명시적 후속 과제는 0건이다. 그 외에 알아둘 것.

- **운영 확인 1건.** `stats→benchmark` 개명으로 이벤트 FQCN이 바뀌었다. kista-scheduler 로그에 `ClassNotFoundException`(옛 `com.kista.stats.application.event.StatsAlertRaisedEvent`)이 보이면 `docs/agents/docker-infra.md` "배포 직전 EPR 정리 런북"의 DELETE를 1회 실행한다. 세 번의 재기동에서 헬스는 모두 정상이었다. → **2026-10-01 운영 확인 완료**: 세 컨테이너 로그 `ClassNotFoundException` 0건, `public`·`trading` `event_publication` 미완료 row 0건 — 정리 불필요.
- **정책 결정이 필요한 트레이드오프 2건.** (1) 가계부 리마인더의 알림 설정 게이트가 notify로 이동해 알림을 꺼둔 사용자도 등록 여부 조회(DB 3회)를 한다 — 초대제 소규모 전제, 사용자가 늘면 user 포트에 "알림 활성 사용자 id" 조회를 추가한다. (2) `notify-profile.changed` Stream은 last-write upsert 특성 때문에 일시 오류 시 재시도하지 않고 다음 변경이 바로잡는다 — 정확한 재시도가 필요해지면 이벤트에 버전(단조 증가값)을 실어야 한다.
- **의도적으로 남긴 의존.** trading → account(26)·broker(44)·matching(67)·privacy(17)는 매매 실행이 그 도메인들을 조합하는 본질적 의존이라 유지. admin → user(18)·notify → user(15)는 관리자 화면과 알림 게이트웨이가 사용자 정보를 읽는 정상 방향(customer/supplier). `TradingAccount.from(Account)` 정의 1곳은 trading 도메인 → account 도메인 의존으로 허용(호출 경계 3곳에 생성자를 복제하는 것보다 낫다).
- **다음 설계 후보.** (1) 신규 브로커가 `BrokerStatisticsPort`를 구현하면 환율 소스 상수(`Broker.TOSS`)를 정책 객체로 승격. (2) `UserPushNotificationRequestedEvent`(프로세스 간 FCM 위임)와 root 내부 채널 라우팅(`UserChannelNotifier`)을 하나의 알림 계약으로 합칠 여지. (3) Stream 컨슈머의 재시도 한도 초과 포기(ack)를 관리자 알림으로 노출.
- **검증 범위.** 모든 단계는 CI Full Test Suite(실제 Postgres·Redis)를 통과했지만, 로컬 2-프로세스 기동(`docs/agents/commands.md`)으로 내부 API 크로스콜을 직접 확인한 적은 없다. 운영 헬스게이트가 대신 확인한 상태다.

## 4. 참고

- 리뷰(결함·이상적 설계·목표 수치): `docs/reviews/2026-09-30-module-coupling-review.md`
- 인계 메모(단계 상태·배포 주의·후속 과제): `docs/reviews/2026-09-30-module-coupling-handoff.md`
- 단계별 상세 경위: `docs/agents/modulith-migration-history.md` "결합도 재검토 1~7단계" 절
- own-type 허용 원장: `docs/agents/own-type-ledger.md`
