# 지침서 05. Spring Modulith 모듈 구성과 kista-api·trading-core 분리 재검토

작성 2026-11-11 (Fable). 근거: `findings/01-vertical-slices.md`(경계 집계), `findings/02-type-duplication.md` E절, `findings/05-frozen-decisions.md` S-01~S-09·S-31, `docs/history/modulith-migration-history.md:132-146`, main import 기준 모듈 의존 집계(2026-11-11), Spring Modulith 2.1 공식 문서(context7 조회), 조기 마감 커밋 `9e542baf7`~`d3d78b0b3`.

**이 문서는 다른 지침서와 달리 사용자 결정이 먼저 필요하다.** 1~4절이 평가와 권고, 5절이 결정 항목, 6절이 결정 뒤의 실행 계획이다.

## 1. 현재 구성

- Gradle 3개 서브프로젝트(`:api` 16,501줄, `:trading-core` 35,581줄, `:shared` 3,436줄), 프로세스 3 role(kista-api, kista-scheduler, kista-trading), Modulith 모델 2개(api 7 + shared 3, trading-core 9 + shared 3), DB 스키마 5개.
- root와 trading-core는 컴파일 의존이 양방향 0이고 내부 HTTP·Redis로만 통신한다.
- Spring Modulith 2.1.1, 감지 전략 `explicitly-annotated`, 모듈 전부 CLOSED(shared 3개만 OPEN).

모듈 의존(main import 수):

```
api.web     -> admin(4) benchmark(3) finance(10) market(1) notify(1) user(2)
api.notify  -> user(15) benchmark(1) finance(1) market(1)
api.admin   -> user(21) benchmark(1)            shared.contract(73)
api.finance -> user(3)
tc.trading  -> matching(128) broker(70) account(40) marketcalendar(18) privacy(14)   shared.kernel(282)
tc.stats    -> trading(51) broker(22) matching(12) account(10) privacy(4)            shared.kernel(77)
tc.notify   -> trading(36) account(3) privacy(1)
tc.account  -> broker(5)
tc.privacy  -> matching(1)
tc.web      -> broker(6) trading(6) account(1) privacy(1)
```

## 2. 프로세스 분리 평가

### 2-1. 분리가 주는 것

기록된 분리 목표는 "`:api → :trading-core` 컴파일 의존 0"과 "매매 프로세스 단일 인스턴스"다(`history:132` 이하). 왜 그것이 필요한지는 문서에 없다. 코드와 배포 구조에서 읽히는 실제 이득은 둘이다.

1. **배포 독립**: 가계부나 관리자 화면을 고쳐 배포해도 매매 프로세스가 재기동되지 않는다.
2. **장애 격리**: root의 메모리 부족이나 버그가 매매 배치를 죽이지 않는다.

둘 다 돈이 걸린 배치에는 실제 가치가 있다.

### 2-2. 분리가 치르는 값

| 비용 | 규모·근거 |
|---|---|
| 경계 코드 | 내부 컨트롤러·HTTP 어댑터·contract·mapper 약 89파일 3,430줄(main의 6.2%) |
| 사용자 복제본 | root → Redis Stream → trading-core `user_notify_profile` 동기화 17파일 약 730줄. 기능 1(전략 등록)·2(마감 주문)·6(알림)이 여기에 의존 |
| 이중 구현 | 예외 처리기 2벌, 보안 셸 2벌, `UserEventResubmitScheduler` 2벌, `PlainDecimalSerializer` 2벌, Alpaca 설정 2벌, 알림 채널 판정 2곳 |
| `shared.kernel` 비대 | 43파일 1,011줄. trading이 282번 import한다. 프로세스를 넘겨야 해서 `Strategy`·`Broker`·`User`의 enum이 소유 모듈을 떠나 이곳에 모였다 |
| 이벤트 | 프로세스를 넘는 이벤트는 EPR이 전달하지 못해 Redis Stream 발행·소비·재발행 골격을 따로 갖는다. EPR 재발행 소유 규칙이 role마다 다르다 |
| 사고 이력 | `@EnableScheduling` 누락으로 자동매매 cron이 배포 기간 내내 돌지 않음, 이벤트 전달 드리프트(`history:136,139`) |
| 진입점 불일치 | UI가 전략·계좌·통계는 trading-core로 직접, 관리자·벤치마크·시장은 root를 거쳐 trading-core로 간다. 같은 "trading 데이터 조회"가 두 경로다 |
| 사용자 화면 가용성 | kista-trading은 단일 인스턴스 stop-first(`stop_grace_period` 200s)다. 매매 프로세스를 배포하면 전략·계좌·통계 화면이 그동안 응답하지 못한다 |

### 2-3. 판정

지금의 분리선은 **업무 영역**(매매 대 나머지)을 따라 그어져 있다. 그런데 2-1의 이득 둘은 업무 영역이 아니라 **실행 역할**(요청을 받는 웹 대 시각에 맞춰 도는 배치)에서 나온다. 선이 잘못된 곳에 그어져 있어서, 이득은 절반만 얻고(매매 화면은 여전히 배치 프로세스에 묶여 있다) 비용은 전부 치른다.

**권고: 코드는 하나의 애플리케이션(Modulith 모델 1개)으로 합치고, 프로세스는 실행 역할로 나눈다.**

```
한 코드베이스 · 한 Modulith 모델 · 한 이미지
 ├─ web role     : 모든 HTTP API (user, finance, admin, trading 조회·명령). blue/green, 다중 인스턴스 가능
 └─ worker role  : 모든 @Scheduled, 매매 배치, Redis 컨슈머, EPR 재발행. 단일 인스턴스, stop-first
```

- role은 지금도 쓰는 `scheduler.enabled` 방식의 플래그로 가른다. kista-api와 kista-scheduler가 이미 그렇게 나뉘어 있고, kista-trading만 별도 코드베이스다.
- 모듈 사이 호출은 전부 프로세스 안 호출이 된다. 내부 HTTP, contract, 사용자 복제본, 이중 구현이 사라진다.
- 배포 독립은 유지된다. infra가 이미 role별 SHA를 따로 선언하므로, worker에 영향이 없는 변경은 worker를 재기동하지 않는다.
- 장애 격리도 유지된다. web과 worker는 여전히 다른 프로세스다.
- 매매 화면이 web role로 옮겨와 worker 배포 중에도 응답한다.

### 2-4. 합칠 때 새로 생기는 문제 (정직한 목록)

합치면 "같은 trading 코드가 두 프로세스에서 돈다"는 조건이 새로 생긴다. 지금은 trading 코드가 한 프로세스에만 있어서 성립하던 것들이 깨진다.

| 문제 | 지금 | 합친 뒤 필요한 것 |
|---|---|---|
| 프로세스 로컬 상태 | 브로커 호출 간격 게이트(`CallIntervalGate`, 메리츠 110ms), `TradingStatsService` 메모리 캐시, `TradingBatchRunState`, 전일 종가 캐시 등이 한 JVM 안에 있다 | 전수 조사 후 항목별 처리: 게이트는 Redis 공유 또는 "브로커 호출은 worker만"으로 좁힘, 캐시는 Redis pub/sub 무효화 또는 TTL |
| 스키마 호환 | trading 스키마 마이그레이션은 expand/contract 예외다(단일 프로세스가 소유) | web과 worker가 다른 버전으로 겹칠 수 있어 trading 스키마도 expand/contract를 따른다 |
| 이벤트 리스너 실행 위치 | 리스너는 발행한 프로세스에서 돈다 | web이 발행한 이벤트의 리스너가 web에서 돌아도 되는지 리스너별로 확인(외부 호출·락 의존) |
| 사용자 발 주문 | 수동 매매·주문 미리보기가 kista-trading에서 배치와 같은 JVM에 있다 | web에서 실행하면 배치와 DB 락으로만 조율된다. 락이 전부 DB·Redis 기반인지 확인 |

이 표의 전수 조사가 6절 단계 0이고, 그 결과가 진행 여부의 관문이다.

### 2-5. 기각한 대안

- **현 구조 유지 + 비대칭만 정리**(관리자 UI도 trading-core를 직접 부르게 해서 admin HTTP 어댑터 7개와 contract를 줄임): 경계 코드의 절반쯤은 줄지만 사용자 복제본, 이중 셸, `shared.kernel`, 모델 2개는 그대로 남는다. 합치지 않기로 하면 이 안을 한다(5절 선택지 B).
- **완전 단일 프로세스**(web과 worker를 한 JVM에): 가장 단순하고 현재 사용자 규모(운영 계좌 8개, 활성 전략 9개)에는 충분하다. 다만 배포마다 매매 시간대를 피해야 하고 화면이 재기동 동안 멈춘다. 권고안은 role 플래그 구조라서, 설정만 바꾸면 이 형태로도 돌릴 수 있다.
- **물리 DB 분리(S-04, 보류 중)**: 권고 방향과 반대다. 폐기를 권한다.
- **프로세스 간 이벤트를 Modulith 이벤트 외부화로 교체**: 조회한 Modulith 2.1 문서의 외부화 설명은 Kafka·AMQP 같은 메시지 브로커 기준이고 Redis Stream 지원은 확인되지 않았다. 브로커를 새로 들이는 일이 되므로 기각.

## 3. Modulith 모듈 구성 평가

### 3-1. 잘 된 것 (유지)

- `matching`이 순수 커널이다(다른 모듈 의존 0, `shared.kernel`만 사용). 백테스트(`stats`)가 같은 커널을 재사용한다.
- `broker`·`marketcalendar`·`user`·`benchmark`·`market`이 다른 업무 모듈에 의존하지 않는다. 의존 방향에 순환이 없다.
- `ApplicationModules.verify()`와 ArchUnit으로 경계를 빌드에서 강제한다.

### 3-2. 문제

1. **CLOSED이지만 사실상 전부 공개다.** NamedInterface가 56개이고, 모듈 대부분이 `domain`·`usecase`·`port`·`event`를 통째로 공개한다. 공개 패키지에 든 코드가 411파일 12,167줄로 main의 22%다. 특히 `port`(아웃바운드 포트, 영속 포트 포함)를 공개해서 다른 모듈이 남의 저장소 포트를 직접 부를 수 있다. 모듈이 숨기는 것이 `application/service`와 `adapter`뿐이다.
2. **`shared.kernel`이 OPEN 덤프다.** Modulith 문서는 OPEN 모듈을 "기존 코드를 점진 이관할 때 쓰는 것이고, 완전히 모듈화된 애플리케이션에서 쓰면 모듈화가 최적이 아니라는 신호"라고 적는다. 2-2에서 본 대로 프로세스 분리가 원인이다.
3. **`web` 셸 모듈 2개.** "여러 모듈을 집계하는 컨트롤러와 전역 advice"를 담는 sink 모듈이다. 어느 모듈에도 속하지 않는 코드를 모듈로 만들어 둔 것이다.
4. **`notify`가 "순수 아웃바운드 게이트웨이"라면서 업무 모듈 4개를 import한다**(api.notify → user·benchmark·finance·market, tc.notify → trading 36). 남의 이벤트를 듣는 리스너가 게이트웨이 안에 있어서다. 알림 문구를 아는 쪽이 게이트웨이인지 업무 모듈인지가 섞여 있다.
5. **`admin`이 업무 모듈이 아니라 화면 단위다.** trading 데이터를 보여주려고 `shared.contract`를 73번 import한다. "관리자가 본다"는 것은 권한이지 업무 영역이 아니다.
6. **작은 모듈에도 같은 의식을 치른다.** `marketcalendar` 493줄, `market` 939줄, `privacy` 1,054줄, `account` 1,065줄이 각각 NamedInterface 2~4개, package-info, 포트·유스케이스 층을 갖는다. package-info만 66개 300줄이다.
7. **같은 ID가 두 모델에 있다**(`notify`, `web`). 문서에서 매번 루트를 붙여 구분한다.
8. **Modulith가 주는 기능 중 직접 만든 것이 있다.**
   - 구조 그림: `flows.yml` + `ArchitectureMapTest`로 수기 관리한다. 모듈 관계도와 모듈별 요약(빈, 이벤트 발행·구독)은 `Documenter`가 생성한다. 흐름·생명주기 탭은 수기로 남기되 모듈 관계 부분은 생성물로 바꿀 수 있다.
   - 미완료 이벤트 재처리: 2.0부터 staleness monitor(`spring.modulith.events.staleness.*`)와 `FailedEventPublications.resubmit`이 있다. 자체 재발행 스케줄러가 이것으로 대체되는지 확인할 가치가 있다.

### 3-3. 조기 마감 커밋이 보여준 것

조기 마감일 처리 하나(커밋 5개)에 main 37개 파일이 바뀌었다. "거래일과 세션"이라는 한 개념이 다음에 흩어져 있기 때문이다.

| 위치 | 내용 |
|---|---|
| `trading/domain/model/DstInfo` 161줄 | DST별 개장·마감 시각표, 정규장 판정 |
| `marketcalendar/domain/model/MarketSessionTimes` | 실제 세션(조기 마감 포함) 기준 거래일 전환 |
| `trading/application/service/SessionTradeDates` | 위 둘을 잇는 static 헬퍼. 서비스 8곳이 호출하고 `MarketCalendarPort`를 인자로 넘긴다 |
| `shared.platform.time.UsTradeDates` | 13개 파일이 참조 |
| `broker/adapter/out/toss/TossPriceApi:163-199` | `DstInfo`의 정규장 판정을 복제(과거 결정 C-02: broker는 trading을 참조할 수 없어서) |
| `trading/adapter/in/schedule/TradingCloseScheduler` 239줄 | "실제 마감 30분 전에 예약 실행", "휴장·조기 마감일의 하트비트 대체" 판단이 인바운드 어댑터 안에 있다 |

관련 파일은 trading 22, broker 7, marketcalendar 4, shared 5, root 2개다.

- **의심(확인 필요)**: `TossPriceApi`의 복제본은 마감 시각을 DST 05:00·표준시 06:00으로 고정한다(`:171`). 조기 마감일(현지 13:00 마감)에는 실제로 장이 끝났는데도 "정규장 진행 중"으로 판정할 수 있다. 이 판정이 캔들 조회 범위에만 쓰여 영향이 작을 수도 있다. 코드를 끝까지 따라가 확인하지는 않았다.
- 구조 결론: 이 개념의 주인은 `marketcalendar`다. `broker`와 `marketcalendar`는 서로 의존이 없으므로 `broker → marketcalendar` 의존은 순환 없이 허용된다. C-02의 "복제 유지" 이유(broker가 trading을 참조할 수 없다)는 시각표를 trading이 아니라 marketcalendar가 가지면 사라진다.
- 스케줄 판단("오늘 마감 배치를 언제 돌릴 것인가")은 도메인 규칙이다. 스케줄러는 그 답을 받아 예약만 한다. 상세는 지침서 06에서 다룬다.

## 4. 목표 모듈 구성 (합친 뒤)

```
com.kista
  user          가입·승인·프로필·인증·알림 설정
  account       브로커 자격증명
  broker        매매 venue + 시세 공급 (지침서 04)
  calendar      거래일·세션·휴장·조기 마감. DstInfo·MarketSessionTimes·SessionTradeDates·UsTradeDates를 흡수
  matching      주문 계산 커널 (순수)
  trading       전략·사이클·주문·배치
    └ privacy   (중첩 모듈) FIDA 기준 매매표 — trading과 stats만 쓴다
  stats         통계·백테스트·포트폴리오 (trading의 읽기 모델)
  market        공포탐욕·시장 지표
  benchmark     주택·ETF 벤치마크
  finance       가계부
  notification  발송 게이트웨이(텔레그램·FCM). 업무 모듈을 import하지 않는다
  ops           런타임 설정·감사 로그·오류 로그 (현 admin에서 남는 것)
  platform      인프라 leaf (보안 필터, 암호화, 스케줄 골격, Redis 골격)
```

규칙:

1. **모듈이 공개하는 것은 `api` 하나와 `events`뿐이다.** `api`는 다른 모듈이 부르는 서비스 타입과 그 입출력 값 타입이다. `port`·`domain`·`usecase`를 통째로 공개하지 않는다. `allowedDependencies`를 package-info에 명시해 의존 그래프를 선언으로 고정한다(예: `allowedDependencies = {"matching", "broker :: api", "account :: api", "calendar :: api"}`).
2. **`shared.kernel`을 해체한다.** `Strategy.*` enum은 trading으로, `Broker`·`Market`은 broker로, `User*`는 user로 돌아간다. 세 모듈 이상이 쓰는 순수 값 타입(`Currency`, `Quantities`, `TimeZones`, `AccountNumberMasker`)만 `platform` 또는 작은 `common`에 남긴다. OPEN 모듈은 `platform` 하나만 둔다.
3. **`shared.contract`를 삭제한다.** 프로세스 경계가 없으면 wire 계약도 없다.
4. **`web` 셸을 없앤다.** 전역 advice·보안 정책은 `platform`으로, 여러 모듈을 집계하는 컨트롤러는 데이터를 소유한 모듈로 옮긴다. 어느 모듈에도 속하지 않는 조립 코드는 모듈로 감지되지 않는 루트 패키지(`com.kista.app`)에 둔다.
5. **`admin`을 해체한다.** 관리자 엔드포인트는 데이터를 소유한 모듈의 `adapter/in/web`에 `Admin*Controller`로 둔다(권한은 `hasRole`). 런타임 설정·감사·오류 로그만 `ops`로 남는다.
6. **알림**: 무엇을 누구에게 어떤 문구로 보낼지는 업무 모듈이 정해서 `notification :: api`를 호출한다. `notification`은 채널 선택과 발송만 한다. 리스너는 이벤트를 발행한 모듈 쪽에 둔다.
7. **작은 모듈은 층을 줄인다.** 지침서 01의 기준(거울 인터페이스 금지)에 더해, 1,500줄 미만 모듈은 `domain`/`application`/`adapter` 3단 패키지를 강제하지 않는다. 가드는 "도메인이 인프라 타입을 모른다"만 남긴다.
8. **privacy는 trading의 중첩 모듈로 둔다.** Modulith 1.3부터 중첩 모듈을 지원한다. 중첩 모듈은 부모와 형제에게만 보이고 부모의 내부에 접근할 수 있다. 다만 stats가 privacy를 4곳에서 쓰므로, 중첩으로 숨기면 그 접근을 `trading :: api`로 돌려야 한다. 돌릴 수 없으면 최상위 모듈로 둔다(실행 시 판정).
9. **문서**: 모듈 관계도와 모듈 요약은 `Documenter` 생성물로 바꾸고, `architecture.md`의 모듈 표와 `docs/architecture-map.md`의 해당 구간을 삭제한다.

프로세스를 합치지 않기로 해도(5절 선택지 B) 1, 4, 5, 6, 7, 9번과 3-3의 calendar 통합은 그대로 적용할 수 있다. 2, 3번은 합칠 때만 가능하다.

## 5. 사용자 결정 항목

| # | 결정 | 선택지 | Fable 권고 |
|---|---|---|---|
| D1 | 프로세스·코드베이스 구조 | **A.** 한 코드베이스·한 모델로 합치고 web/worker role로 분리 / **B.** 현 2-코드베이스 유지, 비대칭만 정리 / **C.** 완전 단일 프로세스 | A. 단, 6절 단계 0의 조사 결과를 보고 최종 확정 |
| D2 | DB 스키마 5개 | 유지(모듈 소유 표시로 값이 싸다) / 통합 | 유지. "다른 모듈 소유 테이블에 FK 금지"는 모듈 규칙으로 남긴다. 물리 DB 분리(S-04)는 폐기 |
| D3 | trading 스키마에도 expand/contract 적용 | D1=A면 필수 | D1에 따름 |
| D4 | `admin` 해체, `web` 셸 해체 | 진행 / 유지 | 진행 (D1과 무관하게 가능) |

D1이 가장 큰 결정이고 비용도 가장 크다(main 약 5,000줄 삭제, 변경 파일 수백 개, 배포 구조 변경, kista-ui 프록시 경로 변경, kista-infra role 정의 변경).

## 6. 실행 계획 (D1=A 기준)

### 단계 0. 관문 조사 (Sonnet 조사, Opus 판정) — 코드 변경 없음

1. trading-core의 프로세스 로컬 상태 전수: static·인스턴스 필드에 상태를 가진 빈(캐시, 게이트, 카운터, in-memory 락, `ConcurrentHashMap`, `AtomicX`), `@PostConstruct` 초기화, 스레드·실행기. 항목마다 "두 프로세스에서 돌면 무엇이 깨지는가"를 적는다.
2. 이벤트 리스너 전수와 실행 위치 요구(외부 호출, 락, 순서 의존).
3. 사용자 발 요청이 브로커를 호출하는 경로 전수(연결 테스트, 미리보기, 잔고 조회, 수동 매매).
4. 락 전수: DB·Redis 기반인지 JVM 기반인지.
5. Opus가 2-4절 표를 항목별로 채우고 "진행 / 조건부 진행 / 중단"을 판정해 사용자에게 보고한다. 산출물: `findings/09-process-local-state.md`.

### 단계 1. 한 JVM에 올린다 (Opus 설계, Sonnet 적용)

1. root가 `:trading-core`를 `implementation`으로 의존하고 `ApiApplication`이 `com.kista` 전체를 스캔한다. Modulith 모델을 1개로 합치고 중복 ID(`notify`, `web`)를 개명한다.
2. role 플래그를 정의한다: `kista.role.web`, `kista.role.worker`. 스케줄러·컨슈머·EPR 재발행은 worker 조건, 컨트롤러는 web 조건. 로컬은 둘 다 켠다.
3. 이 단계에서는 내부 HTTP 호출을 그대로 둔다(자기 자신을 부른다). 동작을 바꾸지 않고 컨텍스트 로드와 전체 테스트가 통과하는지만 본다.
4. Flyway 이력 2벌(root·trading)을 그대로 유지한 채 한 프로세스가 둘 다 실행하게 한다.

### 단계 2. 경계 코드를 걷어낸다 (Sonnet, 모듈 단위)

1. 내부 HTTP 어댑터를 모듈 `api` 직접 호출로 바꾸고 내부 컨트롤러·contract·mapper를 지운다. 순서: market → benchmark → admin(가장 큼).
2. 사용자 복제본(`user_notify_profile`)과 Redis Stream 동기화를 지우고 `user :: api` 직접 조회로 바꾼다. 테이블 drop은 contract 단계로 따로.
3. 이중 구현(예외 처리기, 보안 셸, 재발행 스케줄러, Alpaca 설정)을 한 벌로 합친다.
4. `GradleModuleBoundaryTest`, `InternalApiContractTest`를 삭제한다.

### 단계 3. 모듈 재구성 (4절)

`shared.kernel` 해체 → `calendar` 통합 → `admin`·`web` 해체 → 공개 범위를 `api`·`events`로 좁히고 `allowedDependencies` 선언. 각각 별도 커밋 묶음. 지침서 01(인터페이스 제거)과 같은 파일을 건드리므로 01의 단계 B를 먼저 끝낸다.

### 단계 4. 배포 구조 전환 (kista-infra 세션과 협업)

1. 이미지 1개, role 2개(kista-web, kista-worker)로 compose와 infra role 정의를 바꾼다. worker는 단일 인스턴스·stop-first·`stop_grace_period` 200s를 그대로 물려받는다.
2. kista-ui의 API 진입점이 하나가 된다(`TRADING_API_BASE_URL` 삭제).
3. Gradle 서브프로젝트를 하나로 합친다(마지막. 모듈 경계는 Modulith가 지킨다).

각 단계는 독립 배포 가능해야 한다. 단계 1 뒤에도 운영은 기존 3 role 그대로 돌 수 있다(trading-core jar가 따로 빌드되는 동안).

### D1=B일 때

단계 0·1·2를 하지 않는다. 대신: 관리자 UI가 trading-core의 관리자 엔드포인트를 직접 부르게 해서 admin HTTP 어댑터 7개와 대응 내부 컨트롤러·contract를 지운다. 시장·캘린더·캔들도 같은 방식으로 UI가 직접 부른다. 남는 내부 호출은 벤치마크의 투자 포인트·환율 정도다. 그 뒤 4절의 1, 4, 5, 6, 7, 9번과 calendar 통합을 각 프로세스 안에서 적용한다.

## 7. 재심한 과거 결정

| 결정 | 재심 결과 |
|---|---|
| S-01 2-프로세스 분리(업무 영역 기준) | **뒤집기를 권고**(D1). 분리의 이득은 실행 역할 기준 분리로 더 싸게 얻는다 |
| S-03 스키마 5분리 | 유지 권고(D2) |
| S-04 물리 DB 분리 보류 | **폐기 권고** |
| S-05 Modulith 구조, `explicitly-annotated` | 유지. 모델은 1개로 |
| S-07 NamedInterface만 거친다 / wire 타입은 contract | 전반 유지, 공개 범위를 `api`·`events`로 축소. contract는 D1=A면 소멸 |
| S-08·S-09 own-type 게이트와 원장 | D1=A면 (b) 외부 계약 분리 사유가 대부분 소멸한다. 원장을 다시 판정한다 |
| S-31 EPR 재발행 소유 규칙 | D1=A면 "worker만 true" 한 줄로 단순해진다 |
| C-02 세션 계산 복제 유지(`TossPriceApi`) | **뒤집는다.** 시각표를 calendar가 소유하면 복제 이유가 없다. 조기 마감 미반영 의심도 함께 해소 |
| memory "Pub/Sub @PostConstruct 유지" 등 2026-10-31 확정 항목 | 단계 0 조사에서 항목별로 다시 본다 |

## 8. 하지 말 것

- 단계 0의 판정 없이 단계 1을 시작하지 않는다.
- 합치는 작업과 모듈 재구성을 한 커밋 묶음에 섞지 않는다.
- 매매 배치의 동작(실행 시각, 락, 재개)을 이 지침서의 작업으로 바꾸지 않는다.
- 운영 role 전환(단계 4)을 매매 시간대에 하지 않는다.

## 9. 다른 지침서와의 관계

- 01: 단계 B(UseCase 제거)를 먼저. 단계 C(포트 제거)와 이 지침서 단계 3의 공개 범위 축소는 같은 파일을 건드리므로 순서를 정해 묶는다.
- 04: 브로커 삭제를 가장 먼저. 경계를 넘는 브로커 관련 contract가 줄어든다.
- 06: 스케줄 판단의 도메인 이동, 배치 오케스트레이션.
- 10: T4 ④(내부 API 목록 규약)는 D1=A면 불필요.
- kista-ui 지침서 02(데이터 층, 3경로 프록시), kista-infra 지침서 01(role 정의): D1 결정을 두 세션에 알린다.
