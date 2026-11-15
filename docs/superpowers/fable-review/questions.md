# 실행 중 질문

규칙은 `CHARTER.md` "실행 중 질문" 절(2026-11-14 개정)과 `guides/00-roadmap.md` 9절. 새 항목은 맨 아래에 추가한다. 번호는 Q-01부터 순서대로.

**2026-11-14부터 이 파일은 질문함이 아니라 결정 기록이다.** Fable 세션이 답하지 않는다. 적기 전에 로드맵 9절과 해당 지침서의 "실행 전 보정" 절에 답이 있는지 본다. 상태는 `자체 결정`(실행 세션이 기본값으로 정함), `사용자 결정 필요`(그 태스크만 멈춤), `답변됨`이다.

## 지금까지의 질문을 유형으로 묶은 것

| 유형 | 질문 | 답의 꼴 | 같은 유형이 다시 나오면 |
|---|---|---|---|
| U1 외부 문서와 지침서가 다르다 | Q-01 | 문서를 따라 그 항목 철회 | 로드맵 9-2 U1 |
| U2 코드와 지침서가 다르다 | Q-02 | 그 항목을 뒤 단계로 이동 | 로드맵 9-1, 9-2 U2 |
| U3 스펙에 없는 동작(실측 필요) | Q-03 | 돈이 덜 움직이는 쪽으로 두고 실측 뒤 재론 | 로드맵 9-2 U3 |
| U4 동작 변경의 2차 효과 | Q-04 | 드문 경로·손실 방향 아님이라 그대로 둠 | 로드맵 9-2 U4 |
| U5 배포 승인·시점 | Q-05 | 전후 비교표를 보이고 사용자 승인, 직전 0건 확인 | 로드맵 9-4 |
| U6 중단 조건(보장 수준 차이) | Q-06 | 뒤 단계에서 통째로 처리 | 로드맵 9-5 |
| U7 기존 가드와 충돌 | Q-07 | 뒤 단계로 이동, 가드는 약화하지 않음 | 로드맵 9-3 |

유형별로 다른 지침서의 어느 태스크에서 다시 나올 수 있는지는 `findings/23`(01~07)·`findings/24`(08~15)에 전수가 있고, 그 답은 각 지침서의 "실행 전 보정" 절에 넣었다.

## 항목 틀

```
### Q-NN <한 줄 제목>
- 상태: 자체 결정 | 사용자 결정 필요 | 답변됨
- 묶음·태스크: S2 / 지침서 08 T1
- 걸린 곳: 지침서의 어느 문장(절 번호와 인용)이 무엇과 맞지 않는가, 또는 어느 중단 조건인가
- 근거: `파일:줄`과 확인한 명령·출력(가장 짧은 결정적 한 줄)
- 선택지: 실행 세션이 보는 안 1~3개와 각각의 결과
- 멈춘 범위: 이 질문 때문에 멈춘 태스크, 계속 진행 중인 태스크
- 권고: 실행 세션이 고를 안과 이유(사용자 결정 필요일 때)
- 답: 누가 정했는지(자체 결정 / 사용자, 날짜), 고친 지침서의 절, 커밋 해시
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

### Q-08 T0 BITO·EETH의 메리츠 거래소 코드를 실측하지 않고 넣었다
- 상태: 자체 결정 (배포 뒤 사용자 확인 필요)
- 묶음·태스크: S17 / 지침서 15 T0
- 걸린 곳: 2-4절 "메리츠 계좌로 두 종목의 현재가를 1회씩 조회해 확인한 뒤 넣는다"와 로드맵 9-2 U3 "실계좌 실측은 하지 않는다". 10절 기본값이 "QLD와 같은 매핑을 넣고 배포 뒤 사용자가 확인"으로 정했다.
- 근거: 둘 다 NYSE Arca 상장(`PROGRESS.md` 2차 결정, Alpaca `ARCA`). `MeritzExchangeRegistry`에 QLD와 같은 `"0066","AX"`를 넣었다. `meritz-api.md:42` 실측 목록에는 "실측 전"으로 따로 적었다.
- 선택지: ① 지금 값 유지, 사용자가 확인. 매핑이 틀렸을 때 예상: 시세 조회·주문이 벤더 오류로 실패한다(미실측). 주문이 나가지 않는 방향이라 돈은 움직이지 않는다.
- 멈춘 범위: 없음. 관리자 설정의 두 종목 허용만 확인 뒤로 미룬다.
- 사용자 할 일(순서대로): 1) 배포 2) 관리자 설정에서 허용하기 **전에** 메리츠 계좌의 전략 등록 화면에서 BITO·EETH 현재가가 조회되는지 본다 3) 조회되면 관리자 설정 DCA 허용 종목에 두 종목을 넣는다(저장된 정책 JSON은 backfill되지 않는다 — `TradingPolicySettingsPersistenceAdapter:60-85`. 화면에서 안 되면 `db/migration-trading` jsonb 수정 1건) 4) 조회되지 않으면 그 종목은 허용하지 않고 매핑을 고친다(`meritz-api.md:42`의 실측 목록도 갱신)
- 답: 자체 결정(2026-11-14), 지침서 15의 10절 기본값을 따름
- 검토: (Fable, 2026-11-14, 사용자 요청) 선택지 ① 유지에 동의한다. 두 종목 모두 NYSE Arca 상장이고(EETH는 ProShares 팩트시트로 확인), `MeritzExchangeRegistry`에서 같은 Arca 상장인 SOXL·USD·MAGX·UPRO·QLD가 전부 `0066`/`AX`로 실측돼 있어 다른 값일 근거가 없다. KIS 쪽 `AMEX`/`AMS`와도 맞는다. 틀렸을 때는 조회·주문이 실패하는 방향이라 U3 기본값(돈이 덜 움직이는 쪽)에 맞다. 상태는 그대로 둔다 — 남은 것은 위 "사용자 할 일" 2)의 현재가 조회뿐이고, 조회되면 `meritz.md:42`의 "실측 전"을 지우고 이 항목을 답변됨으로 바꾼다.

### Q-09 T0 EETH 발행사 명칭과 enum 위치
- 상태: 답변됨
- 묶음·태스크: S17 / 지침서 15 T0
- 걸린 곳: 10절 "상수 설명 문자열은 발행사 사이트에서 확인한다", "새 종목은 목록 끝에 더한다".
- 근거: BITO는 ProShares SAI(2024-09-27 개명 "ProShares Bitcoin Strategy ETF" → "ProShares Bitcoin ETF")와 펀드 페이지로 확인했다. EETH는 같은 2024 SAI에 "Ether ETF"로 나오지만 2023 ProShares 글은 "Ether Strategy ETF"라 개명 공시를 직접 보지 못했다. 설명은 기존 대문자 표기로 `PROSHARES BITCOIN ETF`·`PROSHARES ETHER ETF`. 순서: `EnumSet`은 선언 순서로 돌기 때문에 상수를 QLD 뒤·BTC 앞에 두고, 허용 목록·테스트도 UPRO, QLD, BITO, EETH, BTC, ETH로 맞췄다("목록 끝"이 아니라 주식 종목 끝). BTC·ETH는 N18에서 지워지므로 그 뒤에는 목록 끝과 같다.
- 선택지: EETH 명칭이 다르면 설명 문자열 한 줄만 고친다(UI 메타 표시용, DB·주문 영향 없음).
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-14)
- 검토: (Fable, 2026-11-14, 사용자 요청) 명칭 확정, 코드 수정 없음. EETH도 BITO와 같은 날(2024-09-27) "ProShares Ether Strategy ETF" → "ProShares Ether ETF"로 바뀌었다(ETF Database insight report "Before September 27, 2024, the fund was named ProShares Ether Strategy ETF", TrackInsight 현행 표기도 같다. 개명 공시 원문은 찾지 못해 2차 출처 기준). `PROSHARES ETHER ETF`가 맞다. 순서(QLD 뒤·BTC 앞)도 유지 — 지침서의 "목록 끝"은 기존 상수 순서를 건드리지 말라는 뜻이고, `@Enumerated(STRING)`이라 선언 위치는 DB에 영향이 없다.

### Q-10 메리츠 확정 종가 조회의 `mod_stpr_cls_code=1` 주석이 스펙과 반대였다
- 상태: 답변됨
- 묶음·태스크: S8 / 지침서 09 T3(4-4 "메리츠 `mod_stpr_cls_code` 주석을 스펙대로")
- 걸린 곳: `MeritzPriceApi.fetchConfirmedClose`가 `"1"`을 보내며 "수정주가 반영"이라고 주석을 달았다. 스펙은 반대다.
- 근거: `meritz_openapi_spec_20261112.json` 일봉 API `mod_stpr_cls_code`(required Y) — `"0": 분할을 반영해 보정한 주가`, `"1": 보정 전 원래 주가`.
- 선택지: ① 주석만 고치고 `"1"` 유지 — 그 거래일 하루(`from`=`to`)의 종가를 찾는 용도라 거래 당일의 원래 가격이 맞고, 조회일 뒤에 분할이 없으면 두 값은 같다 ② `"0"`으로 변경 — 이후 분할이 생기면 과거 거래일 종가가 보정가로 바뀌어 원장 평가가 소급해서 달라진다.
- 멈춘 범위: 없음. 확정 종가 출처는 지침서 14 T3에서 Alpaca로 옮겨 가며 이 호출은 주문 경로 밖이 된다.
- 답: 자체 결정(2026-11-14) ①. 주석만 스펙대로 고쳤다(동작 불변).
- 검토: (Fable, 2026-11-14, 사용자 요청) ① 확정. 스펙 원문을 다시 확인했다(해외 일봉 `mod_stpr_cls_code` required Y, `"0": 분할을 반영해 보정한 주가`, `"1": 보정 전 원래 주가`). 확정 종가는 그 거래일 체결·원장과 같은 가격 기준이어야 하므로 원주가가 맞고, KIS `KisPriceApi`의 `MODP=0`(수정주가 미반영)과도 같은 기준이다. 주의: 같은 스펙의 국내 일봉은 `0:수정종가(기본값)`, `1:원종가`에 required N으로 정의가 따로 있다 — 해외 값과 뜻은 같지만 필수 여부가 다르니 섞어 읽지 않는다.

### Q-11 `orders` 유일 제약의 키를 지침서(사이클·거래일·leg)보다 넓혔다
- 상태: 자체 결정 (2026-11-16·17 거래일 관찰 뒤 닫는다)
- 묶음·태스크: S11 / 지침서 11 T1(2-2절의 `uq_orders_live_slot`)
- 걸린 곳: 지침서는 "슬롯을 식별하는 컬럼은 `order_leg`"라고 적었지만 코드의 슬롯은 `timing + direction + orderLeg`다(`TradingOrderSlots.ConcreteSlot`, `workflow-batch.md`). VR은 bootstrap 매수(AT_CLOSE)와 사다리 매수(AT_OPEN)가 같은 leg 이름 `VR_BUY_01`을 쓴다(`VrStrategy`).
- 근거: `findings/27-s11-save-paths.md` 2-3 의심2. 운영에서 (사이클·거래일·leg) 기준 살아 있는 중복은 0건이다(2026-11-15). 같은 날 두 모드가 함께 살아 있는 경로는 찾지 못했지만 없다고 확정하지도 못했다(보유가 남은 채 VR 값이 0으로 밀린 날의 bootstrap 대체 분기).
- 선택지: ① 키를 `(strategy_cycle_id, trade_date, timing, direction, order_leg)`로 — 앱의 슬롯 판정과 같아서 정상 경로가 제약에 걸릴 수 없다. 같은 배치가 두 번 돌아 생기는 이중 주문은 timing·direction·leg가 전부 같으므로 똑같이 막는다 ② 지침서대로 `(strategy_cycle_id, trade_date, order_leg)` — 더 좁게 막지만 위 VR 분기가 실제로 일어나면 그 계좌의 마감 접수가 저장 실패로 빠진다.
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-15) ①. 제약의 목적(같은 일이 두 번 돌 때 이중 주문 방지)은 그대로이고, 돈이 덜 움직이는 쪽이 아니라 "정상 주문이 빠지지 않는 쪽"을 골랐다. ②로 좁히려면 VR leg 이름을 timing별로 나눈 뒤(03 전략 축) 인덱스를 다시 만든다.
- 검토: (Fable, 2026-11-15) ① 유지에 동의한다. 근거 둘은 코드와 맞다(`TradingOrderSlots.ConcreteSlot`이 timing·direction·leg, VR bootstrap과 사다리 첫 단이 같은 `VR_BUY_01`). 운영 읽기 전용 조회(2026-11-15 11시 KST): 인덱스 정의가 V21과 같고, 전체 이력에서 같은 (사이클·거래일·leg)에 슬롯이 둘 이상인 경우 0건, 좁은 키(사이클·거래일·leg)로 본 살아 있는 중복 0건, 열린 사이클이 둘 이상인 전략 0건, 기동(10:56 KST) 뒤 `kista-trading` 로그의 제약 위반 0건이다. 지금까지는 두 키가 같은 결과를 내므로 넓힌 키로 잃은 것이 없다. 이 항목이 적지 않은 차이가 하나 있다: 인덱스는 FILLED·PARTIALLY_FILLED도 살아 있는 것으로 보고 앱의 재계산 판정(`findPlannedOrPlacedByCycleAndDate`)은 PLANNED·SUBMITTING·PLACED만 본다(`findings/27` 의심3). 그래서 "정상 경로가 제약에 걸릴 수 없다"는 "같은 거래일에 체결된 슬롯으로 다시 계획하지 않는 한"이라는 조건이 붙는다. 그 조건이 깨지면 저장이 실패해 주문이 나가지 않으므로 돈이 덜 움직이는 쪽이고, 제약이 없었다면 이중 주문이 됐을 경로다. 그대로 둔다. "돈이 덜 움직이는 쪽이 아니라 정상 주문이 빠지지 않는 쪽"이라는 기준은 U3(스펙 공백)가 아니라 U2(코드와 지침서가 다름)의 처리라 맞다. 남은 것: 2026-11-16·17 거래일 관찰(11의 10절). 위반 로그가 0건이고 PRIVACY 가격 보정·VR 롤오버가 있던 날이 정상 완료되면 답변됨으로 바꾼다.

### Q-12 `MarketDataPort` 인터페이스를 만들지 않고 구체 클래스를 주입하게 했다
- 상태: 답변됨
- 묶음·태스크: S15 / 지침서 14 T1(4-1절 "포트 하나")
- 걸린 곳: 14의 4-1절은 `MarketDataPort` 인터페이스 + Alpaca 구현 하나를 적었다. 01 단계 A가 넣은 가드 `interfaces_must_earn_their_keep`은 `application/port/output`의 인터페이스가 구현 2개 이상이거나 의존 역전 허용 목록에 있어야 통과한다. 제거 대기 목록(`single-impl-interfaces.txt`)은 상한 134가 차 있어 새 항목을 받지 않는다.
- 근거: 14가 든 이유("실제 외부 구현이 있다, 테스트 대역과 Mock이 주입받는다")는 01의 유지 조건 셋(구현 2개 이상, 모듈 간 역전, 프로세스 밖 계약)에 없다. 01의 2절은 단일 구현 외부 연동을 "서비스 → 구현 클래스 직접"으로 정했고 테스트 대역은 Mockito가 클래스를 mock한다.
- 선택지: ① 인터페이스 없이 `AlpacaMarketDataAdapter`를 타입 단위 `@NamedInterface("marketdata")`로 공개해 직접 주입 — 가드 그대로. 소비 코드에 벤더 이름이 보인다 ② 의존 역전 허용 목록에 `MarketDataPort`를 더한다 — 구현이 같은 모듈에 있어 목록의 뜻(순환을 끊는 포트)과 맞지 않는다. 가드를 약화한다(로드맵 9-3이 금지).
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-15) ①. 공급자를 바꾸게 되면 클래스 이름과 내부만 바꾼다(메서드 넷과 도메인 타입 시그니처는 벤더 중립이다). 14의 4-1절 문장에 철회를 적었다.
- 검토: (Fable, 2026-11-15) ① 유지에 동의한다. 이유는 "가드가 막아서"가 아니라 01의 2절 원칙(단일 구현 외부 연동은 서비스가 구현 클래스를 직접 쓴다)이다. 가드가 없었어도 같은 결론이다. ②는 구현이 같은 모듈에 있어 의존 역전이 아니므로 기각이 맞다. 보완할 것이 둘 있다. (가) 01 단계 C 4번은 "모듈 밖에서 `adapter/out`을 참조하게 되면 인터페이스를 유지한다"고 적었고 지금이 그 모양이다(trading의 `PriceSourceComparison`이 `broker.adapter.out.marketdata`를 본다). 그 문장의 목적은 모듈 밖이 영속·HTTP 세부를 보지 않게 하는 것인데, 이 클래스는 타입 단위 `@NamedInterface`로 공개 창구를 하나로 좁혔고 시그니처가 도메인 타입뿐이라 목적은 지켜진다. 01의 9절에 "새로 만드는 단일 구현 외부 연동에는 4번을 적용하지 않는다"를 적었다. (나) "공급자를 바꾸면 클래스 이름만 바꾼다"는 소비처가 늘수록 비싸진다. 지금은 main 소비처가 1곳이다. 14 T3이 본 경로를 옮기기 전에 클래스 이름을 벤더 중립(`MarketDataClient` 등)으로 바꾼다 — 14의 11절에 T3의 첫 작업으로 적었다. 상태를 답변됨으로 바꾼다.

### Q-13 T1의 토스 시세 계좌 토큰 실호출을 하지 않았다
- 상태: 자체 결정 (T3 전 사용자 확인 필요)
- 묶음·태스크: S15 / 지침서 14 T1("토스 `GET /api/v1/prices`를 계좌 토큰으로 1회 호출해 확인")
- 걸린 곳: 실행 세션은 실계좌를 부르지 않는다(로드맵 9-2 U3). 운영에 토스 전략이 없다.
- 선택지: ① 호출하지 않고 T3에서 토스 계좌는 Mock처럼 Alpaca 현재가를 쓴다(14의 6절 대체 경로, 11절 기본값) ② 사용자가 로컬 토스 키로 1회 호출을 허락한다 — 되면 T3에서 토스도 계좌 증권사 현재가를 쓴다.
- 멈춘 범위: 없음(T3의 토스 분기만 달라진다).
- 답: 자체 결정(2026-11-15) ①. ②를 원하면 T3 전에 알려 주면 된다. `vendors/toss.md`에 한 줄 남겼다.
- 검토: (Fable, 2026-11-15) 실호출을 하지 않은 것은 맞다(9-2 U3, 9-6 "실계좌 호출이 있어야 확인되는 것은 사용자에게 묻는다"). ①의 뒷부분(T3에서 토스 계좌는 Alpaca 현재가)은 조건을 단다. 14의 3-5절과 2026-11-14 사용자 결정은 "주문 경로의 현재가는 계좌 증권사"이고 Alpaca 무료 현재가를 주문 가격 기준으로 약하다고 봤다. 대체 경로는 그 결정의 예외다. 운영에 토스 전략이 0건이라 지금은 어느 주문에도 닿지 않지만, 토스 전략이 등록되는 순간 예외가 실제 주문 가격(BUY cap의 기준)에 들어간다. 참고로 토스 스펙(`/Users/phs/Downloads/toss/market/marketData.json`)은 `GET /api/v1/prices`의 인증을 `oauth2ClientCredentials`로 적는다. 계좌 토큰도 같은 방식으로 발급되면 호출될 가능성이 높지만 스펙만으로는 단정할 수 없다. 처리: T3 착수 전에 사용자에게 로컬 토스 키로 1회 호출을 요청한다. 되면 토스도 계좌 증권사 현재가를 쓴다. 답이 없거나 안 되면 대체 경로로 가되 토스 전략 등록 전에 다시 확인한다 — 14의 11절에 적었다. 상태는 "자체 결정 (T3 전 사용자 확인 필요)"로 둔다.

### Q-14 `brokerOrDefault`의 기본값을 KIS에서 MOCK으로 바꿨다
- 상태: 자체 결정
- 묶음·태스크: 04 단계 A / KIS 삭제
- 걸린 곳: 계좌 등록 요청에서 `broker`를 생략하면 `AccountService.brokerOrDefault`가 KIS를 돌려줬다. KIS가 없어져 기본값을 새로 정해야 한다.
- 선택지: ① MOCK — 생략해도 실주문 계좌가 만들어지지 않는다 ② `broker`를 필수로 만든다 — 생략 요청이 400이 되는 계약 변경이고 ui와 같은 배포 묶음이 필요하다.
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-15) ①. ui의 계좌 등록 첫 스텝이 증권사 선택을 필수로 받아 실제로는 생략 요청이 없다(`CreateAccountStepper.tsx:79`). ②는 ui가 생략 요청을 보내지 않는 것을 확인한 뒤 따로 한다.

### Q-15 C5에 없던 코인 전용 계약 상수(`ErrorCode`·`DcaSkipReason`)를 T4에서 지울지
- 상태: 사용자 결정 필요
- 묶음·태스크: S16 T1(전수표에서 발견) / 15 T4에서 적용
- 걸린 곳: `constraints.md`는 `ErrorCode` 상수 삭제를 금지한다(kista-ui 계약). C5는 `BROKER_IP_NOT_ALLOWED`만 예외로 허락했다. 전수표(`findings/31` 10절)에서 같은 성격의 상수가 더 나왔다: `ErrorCode.BROKER_WITHDRAWAL_ENABLED`와 `BrokerWithdrawalEnabledException`(바이낸스 전용), `DcaSkipReason`의 `BELOW_MIN_ORDER_AMOUNT`·`EXCHANGE_API_ERROR`·`API_KEY_EXPIRED`(코인 전용, 상수명이 `dca_execution` 값이자 UI 계약). `ApiKeyExpirySource.BROKER_API`는 15의 2-2절이 이미 삭제로 정했다.
- 선택지: ① C5처럼 T4에서 함께 지운다. C2가 코인 `dca_execution` 행을 완전 삭제하므로 DB에 남는 값이 없고, ui는 G1에서 처리 코드를 걷는다(ui 12에 항목 추가 필요) ② 상수만 남기고 발행 코드만 지운다. 계약은 그대로지만 죽은 상수가 남는다.
- 권고: ①. 단 T4 배포 직전 `SELECT DISTINCT skip_reason FROM trading.dca_execution`으로 주식 행에 세 값이 없는지 확인한다.
- 멈춘 범위: 없음(T4 착수 전까지 답이 필요하다. 답이 없으면 ②로 진행해 계약을 깨지 않는다).

## Fable 대기 작업 (2026-11-15 추가)

Fable 한도가 돌아오면 한 세션에서 몰아서 한다. 위에서부터 순서대로 하고, 끝난 항목에는 결과 문서와 커밋 해시를 적는다. 검수만 하고 고치지 않는다. 고칠 결함은 로드맵에 후속 묶음으로 추가한다. 산출물은 `findings/30-post-hoc-review.md` 하나에 절로 나눈다.

### F-01 S18 배포 전 검수 (돈 경로, 미배포)
- 대상: `f105d68db`(08 8절 T8~T10). 리버스 별지점 = 직전 5거래일 종가 평균(실패·5개 미만이면 원장 5행 평균 폴백, 같은 거래일 리버스 LOC 매도가 재사용), 리버스 종료 `>`.
- 볼 것: 원문(`trading-formulas.md`, 08의 원문 인용)과 코드·테스트 일치. 9-2 U4 세 가지(배포 전 저장된 진행 중 리버스 전략 행의 해석, 다음 거래일 주문, 주문 0건 경로). 폴백이 주문을 더 내거나 수량을 부풀리는 쪽인지(U3).
- 운영에 진행 중 INFINITE·리버스 전략이 있으면 읽기 전용 조회 뒤 9-4의 변경 전후 다음 거래일 주문 비교표가 필요한지 판정한다.
- 산출: 배포 가능 / 조건부 / 보류 판정, 결함 목록, 배포 전 확인표(SELECT·기대값·시각). 로드맵 S18 칸에 한 줄.

### F-02 W1(04 단계 A KIS 삭제) 배포 전 검수 (운영 데이터 삭제, 미배포)
- 대상: `830f826b4`·`dfdecd8c4`·`a43b5af51`·`cd18ee952`·`4ecde8638`·`005b4f733`. Opus 리뷰어 검수는 끝났다(Critical·Important 0).
- 볼 것:
  - trading V22: 가드, FK 없는 참조 누락(운영 스캔으로는 `trading_batch_report.strategy_id`·`dca_execution.order_id`만), 덤프 명령으로 복원할 수 있는지.
  - `dfdecd8c4`: 정책 JSON에서 `defaults()`에 없는 enum 키를 버린다. 관리자가 저장하면 사라지는 것, 15의 UPBIT·BINANCE 키에서도 안전한지, 오타 키를 조용히 삼키는 부작용.
  - `supportsOpenAuctionOrders()` false 고정으로 죽은 개장 사전 접수 경로가 다른 broker의 개장 배치에 미치는 영향.
  - 관리자 시세 체인을 접은 것(`TossAdminPriceSource`가 `AdminMarketPricePort` 직접 구현, `enabled()` 삭제)의 동작 보존.
  - 배포 묶음 조건: 세 role 같은 release, kista-ui KIS 제거와 같은 배포(ui `ConfirmStep.tsx:24`의 `'KIS'` 폴백).
- 산출: F-01과 같은 형식. 로드맵 W1 칸에 한 줄.

### F-03 Q-14 사후 판정
- Q-08~Q-13은 Fable 검토가 끝났다. Q-14(`brokerOrDefault` 기본값 MOCK)만 남았다.
- 판정: 유지 / 뒤집기(file:line) / 사용자 결정 필요(선택지·권고). Q-14에 "검토:" 줄을 단다.

### F-04 N1(05 단계 M) 설계 확정과 전환 런북 검토 (별도 세션 권장)
- 선행(S5·W2·04 A)이 모두 main에 들어갔다. M 브랜치를 만들기 전에 `findings/29`의 M 목록과 05 14-3절 전환 배포 절차를 최종 검수한다.
- 15(코인 삭제)를 M 앞에 할지는 05 14-1절의 판정 규칙이 정하므로 다시 판단하지 않는다.
- 산출: M 커밋 순서 확정, 런북 보정(05의 "실행 전 보정" 절), 전환 당일 확인표.

### (선택) F-05 사용자 결정 대기 항목의 권고안
- 11 D1·D2·D3, 12 D0이 N12~N14·N18을 막고 있다. 지침서에 권고가 이미 있으면 건너뛴다.
