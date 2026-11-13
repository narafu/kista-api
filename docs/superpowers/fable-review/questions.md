# 실행 중 질문

규칙은 `CHARTER.md` "실행 중 질문" 절. 새 항목은 맨 아래에 추가한다. 번호는 Q-01부터 순서대로.

## 항목 틀

```
### Q-NN <한 줄 제목>
- 상태: 미답 | 답변됨 | 사용자 결정 필요
- 묶음·태스크: S2 / 지침서 08 T1
- 걸린 곳: 지침서의 어느 문장(절 번호와 인용)이 무엇과 맞지 않는가, 또는 어느 중단 조건인가
- 근거: `파일:줄`과 확인한 명령·출력(가장 짧은 결정적 한 줄)
- 선택지: 실행 세션이 보는 안 1~3개와 각각의 결과
- 멈춘 범위: 이 질문 때문에 멈춘 태스크, 계속 진행 중인 태스크
- 답: (Fable) 지침서 NN의 몇 절 개정 — 한 줄 요약, 커밋 해시
```

## 항목

### Q-01 B3 바이낸스 403의 전제가 공식 문서와 맞지 않는다
- 상태: 답변됨
- 묶음·태스크: S1 / 지침서 04 3절 B3
- 걸린 곳: 04 3절 B3 "HTTP 403(방화벽 차단)을 주문 확정 거절로 처리한다. 접수 여부를 모르는 상태를 거절로 단정한다 — 높음(이중 주문 또는 누락)"
- 근거: 바이낸스 General API Information "HTTP Return Codes"(2026-11-13 확인) — "HTTP `403` return code is used when a WAF (Web Application Firewall) rule has been violated. This can indicate a rate limit violation or a security block." 실행 상태 불명(UNKNOWN)으로 명시된 것은 `5XX`와 `-1006`·`-1007`뿐이다. 403은 매칭 엔진 전 차단이라 접수 불명이 아니다. 현재 코드 `BinanceHttpClient.translate()`는 403을 `rejected=true`로 만들고, `CryptoFailure`가 REJECTED(라운드 SKIP)로 본다. 이중 주문 위험은 없다. findings 12 M4도 "주문 전 차단이라 대체로 안전, 등급 낮음"이다.
- 선택지: ① 그대로 둔다(문서상 미접수 확정). ② 403을 `BrokerRateLimitException`으로 바꿔 `CryptoFailure`가 TRANSIENT(다음 틱 재시도)로 보게 한다(findings 12 M4 — rate limit 위반일 수 있다는 문서 문구 근거, 위험 낮음). ③ 결과 불명(`rejected=false`)으로 바꾼다 — 문서와 어긋나 권하지 않는다.
- 멈춘 범위: B3만 고치지 않았다. B2·B6·B5·B7·B4는 계속 진행.
- 답: (Fable) 지침서 04의 3절 B3 행·"B3 철회 메모"와 8절 단계 C 개정 — B3 철회(선택지 ①, 코드 변경 없음. 테스트 1건과 `binance-api.md` 한 줄만), `20e27a822`


### Q-02 B7 토스 연결 테스트가 "캐시에 새 토큰을 넣는" 경로가 코드에 없다
- 상태: 답변됨
- 묶음·태스크: S1 / 지침서 04 3절 B7, 2-4절 마지막 항목
- 걸린 곳: 04 2-4절 "위 '401 시 1회 재발급'이 이것도 흡수하지만, 연결 테스트가 캐시에 새 토큰을 넣도록 고친다"
- 근거: 스펙(auth) "client 당 유효한 access token 은 1 개입니다. 재발급 시 이전에 발급된 token 은 즉시 무효화됩니다" — 무효화 자체는 맞다. 그러나 토큰 캐시는 계좌 UUID 키(`toss:token:canonical:account:<UUID>`, `TossRedisTokenStore`)이고 연결 테스트 호출 3곳 중
  - 키 교체 `AccountService.updateCredentials`는 저장 뒤 `accountTokenEvictionPort.evict(List.of(accountId))`(`AccountService.java:168`)로 캐시를 이미 지운다 → 다음 호출이 새로 발급한다. 고칠 것 없음.
  - 연결 테스트 `AccountService.test`(`:204`, `verifyCredentials`)와 신규 등록 `register`(`:95`, `verifyAccount`)는 계좌 id가 없다. 같은 client_id를 쓰는 기존 계좌를 찾으려면 appKey로 계좌를 조회해야 하는데 Toss의 중복 키는 계좌번호라(`Account.dedupeKeyOf`) appKey 해시 조회 경로가 없고, broker 모듈은 `Account`를 참조하지 않는다(broker.md).
  - 남는 영향: 운영 중인 키로 연결 테스트를 하면 그 계좌의 다음 호출 1회가 401 `token-revoked` → `TossHttpClient`의 401 복구가 재발급해 흡수한다(기존 동작).
- 선택지: ① 지금은 고치지 않는다 — 단계 D 2-4 토큰 단순화에서 캐시를 client_id(앱키) 키 메모리 캐시로 바꾸면 연결 테스트도 같은 키로 캐시를 갱신할 수 있다(그때 함께). ② 지금 고친다 — `BrokerConnectionTestPort`에 계좌 id 없이 앱키로 캐시를 갱신하는 경로가 필요해 Redis 키 설계를 바꿔야 한다(단계 D와 중복 작업).
- 멈춘 범위: B7만 고치지 않았다. B4는 계속 진행.
- 답: (Fable) 지침서 04의 2-4절 마지막 항목, 3절 B7 행, 8절 단계 C·D-2 개정 — B7을 단계 D-2(토큰 단순화)로 이동(선택지 ①), `20e27a822`

### Q-03 B2 메리츠 판매가능수량에 소수·대출 잔고를 넣지 않은 판단
- 상태: 답변됨
- 묶음·태스크: S1 / 지침서 04 3절 B2 (커밋 `3a08223a1`)
- 걸린 곳: 04 3절 B2 "잔고 조회가 `rmnd_clcd`(일반·소수·대출) 행을 합치지 않고 소수부를 버린다". 보유 현황은 지침서대로 합쳤지만, 판매가능수량은 지침서 문구("합친다")와 다르게 일반 행만 센다. 04에는 "판단이 갈릴 때의 기본값" 절이 없어 실행 세션이 정했다.
- 근거: 스펙(holdings) — 잔고는 `rmnd_clcd` "1" 일반 / "2" 소수 / "3" 대출별 행으로 오고 `rmnd_dqty`는 소수 10자리 문자열이다. 정수 주문이 소수·대출 잔고에서 수량을 끌어다 쓸 수 있는지는 스펙에 없다. kista 매도는 정수 주문만 낸다. 판매가능수량을 부풀리면 매도 전체가 `1543`(매도가능수량 없음)으로 거절된다. 줄이면 소수·대출분이 팔리지 않고 남는다. 현재 구현: `MeritzTradingApi.getSellableQuantity`는 일반 행(구분 누락 포함) 합계를 내림하고, `getPresentBalance`는 모든 행을 종목별로 합쳐 내림하며 평단은 수량 가중이다.
- 선택지: ① 그대로 둔다(거절 위험 쪽을 피함). ② 소수 행도 판매가능수량에 합산한다 — 메리츠가 일반 매도 주문으로 소수 잔고를 함께 소진하는지 실계좌 실측이 먼저 필요하다. ③ 대출 행만 제외하고 일반·소수를 합산한다 — ②와 같은 실측이 필요하다.
- 멈춘 범위: 없음(①로 커밋됨). 운영 계좌에 소수·대출 잔고가 없으면 지금은 동작 차이가 없다.
- 답: 사용자 결정(2026-11-13) — 선택지 ① 그대로 둔다(일반 행만 판매가능수량). 소수·대출 합산은 실계좌 실측 뒤 재론

### Q-04 F6 교체 뒤 리버스 첫날 MOC가 이틀 연속 나갈 수 있다
- 상태: 답변됨
- 묶음·태스크: S2 / 지침서 08 T4(F6)
- 걸린 곳: 08 2절 F6 "도달 가능하면 리버스 첫날 규칙으로 바꾸고". 교체 자체는 했다(커밋 참고). 남는 부작용이 지침서에 없다.
- 근거: 리버스 플래그는 체결 뒤 마감 리포트(`CyclePositionPersistor.computeNewReverseMode`)에서만 켜지고, 첫날 여부는 최근 detail 2행(`CycleOrderComputer.compute:66-67`, `recent[0].reverse && !recent[1].reverse`)으로 정한다. 플래그가 꺼진 채 계획 시점에 `isFinalRound`가 되면(보유 有 등록 — `StrategyCreationPersister:78`이 false 저장, 리버스 종료 직후 예수금 부족 — `nextReverseMode(true)`는 종가만 본다, 리포트~계획 사이 인출, 종가 없는 리포트) 그날 일반모드가 리버스 첫날 규칙으로 MOC(보유 ÷ 분할수/2)를 낸다. MOC 체결 뒤에도 isFinalRound면 그날 리포트에서 플래그가 켜질 수 있고(매도대금으로 예수금이 늘어 풀릴 수도 있다), 켜지면 다음 날 다시 "첫날"로 판정돼 MOC를 한 번 더 낸다. 바꾸기 전에는 1/4 MOC + 다음 날 첫날 MOC였으니 매도량은 줄었다(40분할 200주: 50+7 → 10+9).
- 선택지: ① 그대로 둔다(운영 발생 0건, 경로 드묾). ② 첫날 판정에 "직전 행 날짜에 INFINITE_MOC_SELL 체결이 있으면 첫날이 아님"을 더한다 — trading 서비스 변경. ③ 일반모드 최종회차일에는 주문을 내지 않고 리포트가 플래그를 켠 다음 날 첫날 MOC — 하루 늦게 리버스 진입.
- 관련 경계(리뷰에서 확인, 고치지 않음): (a) 최종회차 MOC 최소 1주는 "리포트 뒤 인출" 경로에서 보유 1~3주면 개장에 이미 접수된 지정가 매도(보유 − 보유/4 = 보유 전량)와 합쳐 보유보다 많이 팔려 한다 — MOC가 증권사에서 거절돼 FAILED·관리자 알림 1회. 최소 1주가 없으면 주문 0건으로 리포트가 돌지 않아 리버스 진입이 멈추므로 거절 쪽을 택했다. (b) 리버스 첫날(`ReverseInfiniteStrategy.buildFirstDayOrders`)과 예산 소진 MOC는 보유 < 분할수/2면 0주라 주문 0건 → 같은 정지 공백이 기존부터 있다. 이 질문의 "다음 날 첫날 MOC"가 소량 보유에서 정확히 여기에 걸린다.
- 멈춘 범위: 없음(①로 커밋). 리버스 종료 조건에 isFinalRound 가드를 넣는 것과 (b)는 범위 밖이라 손대지 않았다.
- 답: 사용자 결정(2026-11-13) — 선택지 ① 그대로 둔다. 지침서 08 7절 T4 기록에 반영

### Q-05 S2 배포 승인 — 진행 중 INFINITE 전략의 변경 전후 주문(T7)
- 상태: 답변됨
- 묶음·태스크: S2 / 지침서 08 T7
- 걸린 곳: 08 4절 T7 "진행 중인 전략에 대해 변경 전후 다음 거래일 주문 미리보기를 나란히 뽑아 사용자에게 보여준다". 승인 전에는 배포를 요청하지 않는다.
- 근거: 운영 DB 읽기 전용 조회(2026-11-13, `ssh kista-server`)로 진행 중 INFINITE 2건(USD 20분할, 둘 다 일반모드, 11-12 마감 원장·종가 58.90)을 얻었다. "전"은 `fb21cf047`(S2 이전)을 별도 worktree로 빌드해, "후"는 `1050a42e3`까지의 커널로 `InfiniteStrategy.buildOrders`를 직접 돌렸다. cap 61.85는 현재가 = 종가 58.90 가정(실제는 접수 직전 현재가로 다시 계산).

  **f08e3a3c** (54주, 평단 69.48, 예수금 464.67, 후반)

  | | 전 | 후 |
  |---|---|---|
  | T / 별% | 17.80 / −0.16 | 17.7959 / −0.1559 |
  | 별지점 | 58.36 | 58.65 |
  | LOC 매수 | 3주 @58.36 | 3주 @58.64 |
  | 보정 3건 | 52.70 / 42.16 / 35.13 | 같음 |
  | LOC 매도 | 13주 @58.37 | 13주 @58.65 |
  | 지정가 매도 | 41주 @83.38 | 같음 |

  **01a1a5ac** (46주, 평단 56.27, 예수금 7411.76, 전반)

  | | 전 | 후 |
  |---|---|---|
  | T / 별% | 5.18 / 0.10 | 5.1767 / 0.0965 |
  | 별지점 | 61.90 | 61.70 |
  | 매수① | 4주 @56.27 | 같음 |
  | 매수② | 4주 @61.90 (cap 61.85 걸림) | 4주 @61.69 (cap 안 걸림) |
  | 보정 3건 | 55.55 / 50.00 / 45.45 | 같음 |
  | LOC 매도 | 11주 @61.91 | 11주 @61.70 |
  | 지정가 매도 | 35주 @67.52 | 같음 |

  두 건 모두 수량·최종회차·리버스 진입 판정은 같고 LOC 가격만 바뀐다. 보유 0 전략이 없어 첫 매수 변경(F4)은 지금 해당 없음(새 사이클부터 적용).
- 선택지: ① 승인 — T1~T4(`66213af3c`~`1050a42e3`)를 한 번에 배포한다. 지침서는 T1~T3이라 했지만 T4도 주문 코드 변경이다. 시점은 마감 리포트 뒤·22:00 개장 배치 전. 직전에 그 거래일의 INFINITE BUY 주문(PLANNED·SUBMITTING·PLACED)과 열린 AT_OPEN `INFINITE_MOC_SELL`이 0건인지 SELECT로 확인한다(배포 전 저장된 옛 가격 행이 하루 유지되거나 옛 반반 행·옛 MOC와 이중 주문이 나는 것을 막기 위해). ② 보류 — 커밋은 로컬에 둔다(push 안 함).
- 멈춘 범위: 배포만 멈춤. S2 구현·테스트·문서는 끝났다.
- 답: 사용자 결정(2026-11-13) — 선택지 ① 승인. 지침서 08의 4절 배포 시점 문장과 7절 "T7 배포 승인" 개정 — T1~T4 한 번에, 마감 리포트 뒤·22:00 전, 직전 0건 SELECT 확인. push는 사용자 요청 시, `d2f40a341`

### Q-06 P5 스트림 직접 호출이 EPR 재시도와 같은 보장을 주지 않는다
- 상태: 답변됨
- 묶음·태스크: S5 / 지침서 05 3절 P5
- 걸린 곳: 10절 중단 조건 "P5에서 스트림 복구가 EPR 재시도와 같은 보장을 주지 않는다", P5 "보장이 부족하면 중단하고 보고한다".
- 근거: 지금은 `UserEventStreamBridge` → `UserEventRepublisher`(@Transactional) 재발행이 커밋되면 메시지를 ack하고, 그 뒤 `TradingUserCascadeListener`·`UserNotifyProfileSyncListener`(AFTER_COMMIT) 실패는 `trading.event_publication`에 FAILED로 남는다. 컨슈머가 유스케이스·포트를 직접 부르게 바꾸면 실패가 스트림 컨슈머로 올라오는데, 이 경로는 세 가지가 다르다.
  1. 재시도 기간: 지금은 `UserEventResubmitScheduler`(T, `adapter/in/schedule/UserEventResubmitScheduler.java:36`)가 10분마다 **무기한** 재발행한다. 스트림은 `UserEventStreamConsumer.MAX_DELIVERIES=24`(5분 × 24 ≈ 2시간) 뒤 포기(ack)하고 관리자에게 알린다.
  2. 조용히 버려지는 예외: `UserEventStreamConsumer.ackOnFailure`가 `JacksonException`·`IllegalArgumentException`·`NullPointerException`을 poison으로 보고 바로 ack한다. 이 경로는 `RedisStreamSubscriber.handle`(`:123`)에서 warn 한 줄만 남기고 `onGiveUp`(관리자 알림)을 부르지 않는다. 지금은 정리 로직이 IAE·NPE를 던져도 EPR이 재시도하고 첫 실패에 관리자 알림을 보낸다(`UserEventResubmitScheduler.java:51`).
  3. 내구성: 지금은 ack 이후 처리가 Postgres 행과 재기동 시 재발행(`deploy/server/docker-compose.yml:60` kista-trading `REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART=true`)에 기대고, 바꾸면 Redis pending 목록에만 기댄다.
  `maxDeliveries`·`ackOnFailure`를 조정해 맞추는 것은 새 보장을 설계하는 일이라 하지 않았다.
- 선택지:
  1. 컨슈머가 `UserDeletedEvent`·`UserNotifyProfileChangedEvent`(shared.kernel) 대신 **trading 내부 전용 이벤트**(예: `TradingUserErasureRequested`)를 재발행한다. R의 `UserEventStreamPublisher`는 이 타입을 듣지 않으므로 합병 후에도 순환이 생기지 않고 EPR 재시도·재기동 재발행 보장이 그대로 남는다. 리스너 메서드 파라미터 타입이 바뀌어 EPR listener_id·event_type이 달라지므로 배포 직전 미완료 행 0건을 확인해야 한다(docker-infra.md "배포 직전 EPR 정리 런북"). `UserEventResubmitScheduler`(T) 필터 대상도 새 타입으로 바뀐다.
  2. 지침서대로 직접 호출하되 스트림 정책을 EPR 수준으로 올린다: poison은 역직렬화 실패만, `maxDeliveries` 무제한, 첫 실패 관리자 알림 훅 추가. 내구성 차이(3)는 남는다.
  3. P5를 단계 M으로 미룬다. 합병 커밋에서 스트림을 통째로 지우고 R 발행을 T 리스너가 직접 받게 하면(findings/10 E-3) 지금의 재발행 경로가 사라지며 EPR 보장은 그대로다. 그때까지는 순환이 생길 일이 없다(지금은 두 프로세스).
- 멈춘 범위: P5만 멈춤. S5의 P1·P2·P7은 계속 진행.
- 답: 사용자 결정(2026-11-13) — 선택지 3. 지침서 05의 3절 P5·단계 P 완료 판정, 4-1절 M9, 4-2절, 10절 개정 — 단계 P에서는 손대지 않고 M9에서 스트림 경로를 통째로 지운다(T 리스너 두 개는 남겨 EPR 보장 유지), `d2f40a341`

### Q-07 P2 ClockConfig를 S로 옮기면 기존 가드 두 개와 부딪힌다
- 상태: 답변됨
- 묶음·태스크: S5 / 지침서 05 3절 P2
- 걸린 곳: P2 "`ClockConfig`·`RedisBlacklistAdapter`를 S로 옮겨 한 벌로". `RedisBlacklistAdapter`는 옮겼다(trading-core 클래스 삭제, `PlatformAutoConfiguration`이 `@ConditionalOnMissingBean(TokenBlacklistPort)`로 platform `RedisTokenBlacklistReader`를 올림). `ClockConfig`는 옮기지 못했다.
- 근거: `Clock` 빈 본문은 `Clock.system(TimeZones.KST)`인데 S에서 이 빈을 둘 수 있는 곳은 `shared.platform`뿐이다(`shared.kernel`은 Spring 없는 JDK 전용). 그런데 `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`(`:99`)가 platform → `shared.kernel.TimeZones` 참조를 막고, `zoneId_of_must_only_be_called_in_TimeZones`(`:136`)가 platform에서 `ZoneId.of("Asia/Seoul")`을 직접 쓰는 것도 막는다. `main_code_must_not_read_system_clock_directly`의 예외 목록도 두 `ClockConfig` FQCN(`:179-180`)이다.
- 선택지:
  1. 단계 M으로 미룬다. 합칠 때 둘 중 하나를 지우면 끝난다(본문이 같다, findings/10 A3). 지금은 두 프로세스라 충돌이 없다.
  2. 가드를 고친다: `TimeZones`를 `shared.platform.time`으로 옮기거나(전 코드 import 변경) platform 예외를 하나 추가하고 `ClockConfig`를 platform으로 옮긴다.
- 멈춘 범위: P2의 Clock 항목만. 블랙리스트·`fearGreedRestClient` 개명은 진행했다. Alpaca 3벌 → 1벌은 하지 않았다 — 충돌이 아니고(findings/10 A1·A4, 빈 이름·properties FQCN이 모두 다름) 합치려면 R benchmark와 T marketcalendar·stats가 함께 쓸 새 platform 타입이 필요해 단계 X/R에서 alpaca 소유 모듈이 정해질 때 하는 편이 낫다.
- 답: (Fable) 지침서 05의 3절 P2와 4-1절 M9 개정 — 선택지 ①. `ClockConfig`는 P2에서 빼고 M9에서 T 쪽 한 벌 삭제(가드 예외 목록의 T FQCN도 함께), Alpaca 3벌 → 1벌은 단계 X·R로 이동, `ee2027ca0`
