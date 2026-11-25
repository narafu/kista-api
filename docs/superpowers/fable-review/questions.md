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
- 답: 자체 결정(2026-11-14), 지침서 15의 10절 기본값을 따름. 사후 판정(2026-11-17) 유지, `findings/30` 6절
- 검토: (Fable, 2026-11-14, 사용자 요청) 선택지 ① 유지에 동의한다. 두 종목 모두 NYSE Arca 상장이고(EETH는 ProShares 팩트시트로 확인), `MeritzExchangeRegistry`에서 같은 Arca 상장인 SOXL·USD·MAGX·UPRO·QLD가 전부 `0066`/`AX`로 실측돼 있어 다른 값일 근거가 없다. KIS 쪽 `AMEX`/`AMS`와도 맞는다. 틀렸을 때는 조회·주문이 실패하는 방향이라 U3 기본값(돈이 덜 움직이는 쪽)에 맞다. 상태는 그대로 둔다 — 남은 것은 위 "사용자 할 일" 2)의 현재가 조회뿐이고, 조회되면 `meritz.md:42`의 "실측 전"을 지우고 이 항목을 답변됨으로 바꾼다.

### Q-09 T0 EETH 발행사 명칭과 enum 위치
- 상태: 답변됨
- 묶음·태스크: S17 / 지침서 15 T0
- 걸린 곳: 10절 "상수 설명 문자열은 발행사 사이트에서 확인한다", "새 종목은 목록 끝에 더한다".
- 근거: BITO는 ProShares SAI(2024-09-27 개명 "ProShares Bitcoin Strategy ETF" → "ProShares Bitcoin ETF")와 펀드 페이지로 확인했다. EETH는 같은 2024 SAI에 "Ether ETF"로 나오지만 2023 ProShares 글은 "Ether Strategy ETF"라 개명 공시를 직접 보지 못했다. 설명은 기존 대문자 표기로 `PROSHARES BITCOIN ETF`·`PROSHARES ETHER ETF`. 순서: `EnumSet`은 선언 순서로 돌기 때문에 상수를 QLD 뒤·BTC 앞에 두고, 허용 목록·테스트도 UPRO, QLD, BITO, EETH, BTC, ETH로 맞췄다("목록 끝"이 아니라 주식 종목 끝). BTC·ETH는 N18에서 지워지므로 그 뒤에는 목록 끝과 같다.
- 선택지: EETH 명칭이 다르면 설명 문자열 한 줄만 고친다(UI 메타 표시용, DB·주문 영향 없음).
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-14). 사후 판정(2026-11-17) 유지, `findings/30` 6절
- 검토: (Fable, 2026-11-14, 사용자 요청) 명칭 확정, 코드 수정 없음. EETH도 BITO와 같은 날(2024-09-27) "ProShares Ether Strategy ETF" → "ProShares Ether ETF"로 바뀌었다(ETF Database insight report "Before September 27, 2024, the fund was named ProShares Ether Strategy ETF", TrackInsight 현행 표기도 같다. 개명 공시 원문은 찾지 못해 2차 출처 기준). `PROSHARES ETHER ETF`가 맞다. 순서(QLD 뒤·BTC 앞)도 유지 — 지침서의 "목록 끝"은 기존 상수 순서를 건드리지 말라는 뜻이고, `@Enumerated(STRING)`이라 선언 위치는 DB에 영향이 없다.

### Q-10 메리츠 확정 종가 조회의 `mod_stpr_cls_code=1` 주석이 스펙과 반대였다
- 상태: 답변됨
- 묶음·태스크: S8 / 지침서 09 T3(4-4 "메리츠 `mod_stpr_cls_code` 주석을 스펙대로")
- 걸린 곳: `MeritzPriceApi.fetchConfirmedClose`가 `"1"`을 보내며 "수정주가 반영"이라고 주석을 달았다. 스펙은 반대다.
- 근거: `meritz_openapi_spec_20261112.json` 일봉 API `mod_stpr_cls_code`(required Y) — `"0": 분할을 반영해 보정한 주가`, `"1": 보정 전 원래 주가`.
- 선택지: ① 주석만 고치고 `"1"` 유지 — 그 거래일 하루(`from`=`to`)의 종가를 찾는 용도라 거래 당일의 원래 가격이 맞고, 조회일 뒤에 분할이 없으면 두 값은 같다 ② `"0"`으로 변경 — 이후 분할이 생기면 과거 거래일 종가가 보정가로 바뀌어 원장 평가가 소급해서 달라진다.
- 멈춘 범위: 없음. 확정 종가 출처는 지침서 14 T3에서 Alpaca로 옮겨 가며 이 호출은 주문 경로 밖이 된다.
- 답: 자체 결정(2026-11-14) ①. 주석만 스펙대로 고쳤다(동작 불변). 사후 판정(2026-11-17) 유지, `findings/30` 6절
- 검토: (Fable, 2026-11-14, 사용자 요청) ① 확정. 스펙 원문을 다시 확인했다(해외 일봉 `mod_stpr_cls_code` required Y, `"0": 분할을 반영해 보정한 주가`, `"1": 보정 전 원래 주가`). 확정 종가는 그 거래일 체결·원장과 같은 가격 기준이어야 하므로 원주가가 맞고, KIS `KisPriceApi`의 `MODP=0`(수정주가 미반영)과도 같은 기준이다. 주의: 같은 스펙의 국내 일봉은 `0:수정종가(기본값)`, `1:원종가`에 required N으로 정의가 따로 있다 — 해외 값과 뜻은 같지만 필수 여부가 다르니 섞어 읽지 않는다.

### Q-11 `orders` 유일 제약의 키를 지침서(사이클·거래일·leg)보다 넓혔다
- 상태: 답변됨 (2026-11-20, 관찰 통과)
- 묶음·태스크: S11 / 지침서 11 T1(2-2절의 `uq_orders_live_slot`)
- 걸린 곳: 지침서는 "슬롯을 식별하는 컬럼은 `order_leg`"라고 적었지만 코드의 슬롯은 `timing + direction + orderLeg`다(`TradingOrderSlots.ConcreteSlot`, `workflow-batch.md`). VR은 bootstrap 매수(AT_CLOSE)와 사다리 매수(AT_OPEN)가 같은 leg 이름 `VR_BUY_01`을 쓴다(`VrStrategy`).
- 근거: `findings/27-s11-save-paths.md` 2-3 의심2. 운영에서 (사이클·거래일·leg) 기준 살아 있는 중복은 0건이다(2026-11-15). 같은 날 두 모드가 함께 살아 있는 경로는 찾지 못했지만 없다고 확정하지도 못했다(보유가 남은 채 VR 값이 0으로 밀린 날의 bootstrap 대체 분기).
- 선택지: ① 키를 `(strategy_cycle_id, trade_date, timing, direction, order_leg)`로 — 앱의 슬롯 판정과 같아서 정상 경로가 제약에 걸릴 수 없다. 같은 배치가 두 번 돌아 생기는 이중 주문은 timing·direction·leg가 전부 같으므로 똑같이 막는다 ② 지침서대로 `(strategy_cycle_id, trade_date, order_leg)` — 더 좁게 막지만 위 VR 분기가 실제로 일어나면 그 계좌의 마감 접수가 저장 실패로 빠진다.
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-15) ①. 제약의 목적(같은 일이 두 번 돌 때 이중 주문 방지)은 그대로이고, 돈이 덜 움직이는 쪽이 아니라 "정상 주문이 빠지지 않는 쪽"을 골랐다. ②로 좁히려면 VR leg 이름을 timing별로 나눈 뒤(03 전략 축) 인덱스를 다시 만든다. 사후 판정(2026-11-17) 유지(관찰 대기), `findings/30` 6절.
- 검토: (Fable, 2026-11-15) ① 유지에 동의한다. 근거 둘은 코드와 맞다(`TradingOrderSlots.ConcreteSlot`이 timing·direction·leg, VR bootstrap과 사다리 첫 단이 같은 `VR_BUY_01`). 운영 읽기 전용 조회(2026-11-15 11시 KST): 인덱스 정의가 V21과 같고, 전체 이력에서 같은 (사이클·거래일·leg)에 슬롯이 둘 이상인 경우 0건, 좁은 키(사이클·거래일·leg)로 본 살아 있는 중복 0건, 열린 사이클이 둘 이상인 전략 0건, 기동(10:56 KST) 뒤 `kista-trading` 로그의 제약 위반 0건이다. 지금까지는 두 키가 같은 결과를 내므로 넓힌 키로 잃은 것이 없다. 이 항목이 적지 않은 차이가 하나 있다: 인덱스는 FILLED·PARTIALLY_FILLED도 살아 있는 것으로 보고 앱의 재계산 판정(`findPlannedOrPlacedByCycleAndDate`)은 PLANNED·SUBMITTING·PLACED만 본다(`findings/27` 의심3). 그래서 "정상 경로가 제약에 걸릴 수 없다"는 "같은 거래일에 체결된 슬롯으로 다시 계획하지 않는 한"이라는 조건이 붙는다. 그 조건이 깨지면 저장이 실패해 주문이 나가지 않으므로 돈이 덜 움직이는 쪽이고, 제약이 없었다면 이중 주문이 됐을 경로다. 그대로 둔다. "돈이 덜 움직이는 쪽이 아니라 정상 주문이 빠지지 않는 쪽"이라는 기준은 U3(스펙 공백)가 아니라 U2(코드와 지침서가 다름)의 처리라 맞다. 남은 것: 2026-11-16·17 거래일 관찰(11의 10절). 위반 로그가 0건이고 PRIVACY 가격 보정·VR 롤오버가 있던 날이 정상 완료되면 답변됨으로 바꾼다.
- 관찰(2026-11-20, 읽기 전용): 거래일 11-17~19에 `kista-trading` 로그의 `uq_orders_live_slot`·`uq_strategy_cycle_open`·`ConstraintViolation`은 0건이고, 세 거래일 개장·마감은 정상 완료됐다. 답변됨으로 바꾼다.

### Q-12 `MarketDataPort` 인터페이스를 만들지 않고 구체 클래스를 주입하게 했다
- 상태: 답변됨
- 묶음·태스크: S15 / 지침서 14 T1(4-1절 "포트 하나")
- 걸린 곳: 14의 4-1절은 `MarketDataPort` 인터페이스 + Alpaca 구현 하나를 적었다. 01 단계 A가 넣은 가드 `interfaces_must_earn_their_keep`은 `application/port/output`의 인터페이스가 구현 2개 이상이거나 의존 역전 허용 목록에 있어야 통과한다. 제거 대기 목록(`single-impl-interfaces.txt`)은 상한 134가 차 있어 새 항목을 받지 않는다.
- 근거: 14가 든 이유("실제 외부 구현이 있다, 테스트 대역과 Mock이 주입받는다")는 01의 유지 조건 셋(구현 2개 이상, 모듈 간 역전, 프로세스 밖 계약)에 없다. 01의 2절은 단일 구현 외부 연동을 "서비스 → 구현 클래스 직접"으로 정했고 테스트 대역은 Mockito가 클래스를 mock한다.
- 선택지: ① 인터페이스 없이 `AlpacaMarketDataAdapter`를 타입 단위 `@NamedInterface("marketdata")`로 공개해 직접 주입 — 가드 그대로. 소비 코드에 벤더 이름이 보인다 ② 의존 역전 허용 목록에 `MarketDataPort`를 더한다 — 구현이 같은 모듈에 있어 목록의 뜻(순환을 끊는 포트)과 맞지 않는다. 가드를 약화한다(로드맵 9-3이 금지).
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-15) ①. 공급자를 바꾸게 되면 클래스 이름과 내부만 바꾼다(메서드 넷과 도메인 타입 시그니처는 벤더 중립이다). 14의 4-1절 문장에 철회를 적었다. 사후 판정(2026-11-17) 유지, `findings/30` 6절.
- 검토: (Fable, 2026-11-15) ① 유지에 동의한다. 이유는 "가드가 막아서"가 아니라 01의 2절 원칙(단일 구현 외부 연동은 서비스가 구현 클래스를 직접 쓴다)이다. 가드가 없었어도 같은 결론이다. ②는 구현이 같은 모듈에 있어 의존 역전이 아니므로 기각이 맞다. 보완할 것이 둘 있다. (가) 01 단계 C 4번은 "모듈 밖에서 `adapter/out`을 참조하게 되면 인터페이스를 유지한다"고 적었고 지금이 그 모양이다(trading의 `PriceSourceComparison`이 `broker.adapter.out.marketdata`를 본다). 그 문장의 목적은 모듈 밖이 영속·HTTP 세부를 보지 않게 하는 것인데, 이 클래스는 타입 단위 `@NamedInterface`로 공개 창구를 하나로 좁혔고 시그니처가 도메인 타입뿐이라 목적은 지켜진다. 01의 9절에 "새로 만드는 단일 구현 외부 연동에는 4번을 적용하지 않는다"를 적었다. (나) "공급자를 바꾸면 클래스 이름만 바꾼다"는 소비처가 늘수록 비싸진다. 지금은 main 소비처가 1곳이다. 14 T3이 본 경로를 옮기기 전에 클래스 이름을 벤더 중립(`MarketDataClient` 등)으로 바꾼다 — 14의 11절에 T3의 첫 작업으로 적었다. 상태를 답변됨으로 바꾼다.

### Q-13 T1의 토스 시세 계좌 토큰 실호출을 하지 않았다
- 상태: 답함(2026-11-21)
- 묶음·태스크: S15 / 지침서 14 T1("토스 `GET /api/v1/prices`를 계좌 토큰으로 1회 호출해 확인")
- 걸린 곳: 실행 세션은 실계좌를 부르지 않는다(로드맵 9-2 U3). 운영에 토스 전략이 없다.
- 선택지: ① 호출하지 않고 T3에서 토스 계좌는 Mock처럼 Alpaca 현재가를 쓴다(14의 6절 대체 경로, 11절 기본값) ② 사용자가 로컬 토스 키로 1회 호출을 허락한다 — 되면 T3에서 토스도 계좌 증권사 현재가를 쓴다.
- 멈춘 범위: 없음(T3의 토스 분기만 달라진다).
- 답: 자체 결정(2026-11-15) ①. ②를 원하면 T3 전에 알려 주면 된다. `vendors/toss.md`에 한 줄 남겼다. 사후 판정(2026-11-17) 사용자 결정 필요(T3 전 1회 호출 여부), `findings/30` 6절. T3 적용(2026-11-20): 토스 계좌 현재가는 `TossBrokerAdapter.currentPrices` → `MarketDataClient.latestPrices`(대체 경로)로 갔다. 토스 전략 등록 전 재확인은 `vendors/toss.md`에 적었다.
- 최종 답(2026-11-21): 운영 토스 계좌 키로 1회 읽기 전용 호출(토큰 200, prices 200 헤더 유무 모두), T3 후속으로 계좌 토큰 시세 적용.
- 검토: (Fable, 2026-11-15) 실호출을 하지 않은 것은 맞다(9-2 U3, 9-6 "실계좌 호출이 있어야 확인되는 것은 사용자에게 묻는다"). ①의 뒷부분(T3에서 토스 계좌는 Alpaca 현재가)은 조건을 단다. 14의 3-5절과 2026-11-14 사용자 결정은 "주문 경로의 현재가는 계좌 증권사"이고 Alpaca 무료 현재가를 주문 가격 기준으로 약하다고 봤다. 대체 경로는 그 결정의 예외다. 운영에 토스 전략이 0건이라 지금은 어느 주문에도 닿지 않지만, 토스 전략이 등록되는 순간 예외가 실제 주문 가격(BUY cap의 기준)에 들어간다. 참고로 토스 스펙(`/Users/phs/Downloads/toss/market/marketData.json`)은 `GET /api/v1/prices`의 인증을 `oauth2ClientCredentials`로 적는다. 계좌 토큰도 같은 방식으로 발급되면 호출될 가능성이 높지만 스펙만으로는 단정할 수 없다. 처리: T3 착수 전에 사용자에게 로컬 토스 키로 1회 호출을 요청한다. 되면 토스도 계좌 증권사 현재가를 쓴다. 답이 없거나 안 되면 대체 경로로 가되 토스 전략 등록 전에 다시 확인한다 — 14의 11절에 적었다. 상태는 "자체 결정 (T3 전 사용자 확인 필요)"로 둔다.

### Q-14 `brokerOrDefault`의 기본값을 KIS에서 MOCK으로 바꿨다
- 상태: 자체 결정
- 묶음·태스크: 04 단계 A / KIS 삭제
- 걸린 곳: 계좌 등록 요청에서 `broker`를 생략하면 `AccountService.brokerOrDefault`가 KIS를 돌려줬다. KIS가 없어져 기본값을 새로 정해야 한다.
- 선택지: ① MOCK — 생략해도 실주문 계좌가 만들어지지 않는다 ② `broker`를 필수로 만든다 — 생략 요청이 400이 되는 계약 변경이고 ui와 같은 배포 묶음이 필요하다.
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-15) ①. ui의 계좌 등록 첫 스텝이 증권사 선택을 필수로 받아 실제로는 생략 요청이 없다(`CreateAccountStepper.tsx:79`). ②는 ui가 생략 요청을 보내지 않는 것을 확인한 뒤 따로 한다. 사후 판정(2026-11-17) 유지, `findings/30` 3절.
- 검토: (Fable, 2026-11-16, F-03) 유지. ②는 하지 않는다. 호출처는 `register`(`AccountService.java:54`)와 `test`(`:199`)이고 MOCK 계좌는 주문 API를 부르지 않는다. ui의 생략 경로는 0건이라(ui 회신, `ConfirmStep.tsx:31,38` — 이 항목이 든 `CreateAccountStepper.tsx:79`보다 이쪽이 근거다) 필수로 바꿔도 얻는 것이 계약이 엄격해지는 것뿐이다. `findings/30` 3절.

### Q-15 C5에 없던 코인 전용 계약 상수(`ErrorCode`·`DcaSkipReason`)를 T4에서 지울지
- 상태: 답변됨
- 묶음·태스크: S16 T1(전수표에서 발견) / 15 T4에서 적용
- 걸린 곳: `constraints.md`는 `ErrorCode` 상수 삭제를 금지한다(kista-ui 계약). C5는 `BROKER_IP_NOT_ALLOWED`만 예외로 허락했다. 전수표(`findings/31` 10절)에서 같은 성격의 상수가 더 나왔다: `ErrorCode.BROKER_WITHDRAWAL_ENABLED`와 `BrokerWithdrawalEnabledException`(바이낸스 전용), `DcaSkipReason`의 `BELOW_MIN_ORDER_AMOUNT`·`EXCHANGE_API_ERROR`·`API_KEY_EXPIRED`(코인 전용, 상수명이 `dca_execution` 값이자 UI 계약). `ApiKeyExpirySource.BROKER_API`는 15의 2-2절이 이미 삭제로 정했다.
- 선택지: ① C5처럼 T4에서 함께 지운다. C2가 코인 `dca_execution` 행을 완전 삭제하므로 DB에 남는 값이 없고, ui는 G1에서 처리 코드를 걷는다(ui 12에 항목 추가 필요) ② 상수만 남기고 발행 코드만 지운다. 계약은 그대로지만 죽은 상수가 남는다.
- 권고: ①. 단 T4 배포 직전 `SELECT DISTINCT skip_reason FROM trading.dca_execution`으로 주식 행에 세 값이 없는지 확인한다.
- 멈춘 범위: 없음(T4 착수 전까지 답이 필요하다. 답이 없으면 ②로 진행해 계약을 깨지 않는다).
- 검토: (Fable, 2026-11-16) ①을 권고한다. 사용자 확인만 남았다. 근거 셋. (가) 운영 `trading.dca_execution`의 `skip_reason` 분포(2026-11-16 조회): `BELOW_MIN_ORDER_AMOUNT` 4건은 전부 ETH 전략이고 주식 행에는 `CONDITION_NOT_MET`·`INSUFFICIENT_BALANCE`·`NO_HOLDINGS`뿐이다. `EXCHANGE_API_ERROR`·`API_KEY_EXPIRED`는 0건이다. C2가 코인 행을 지우면 세 값은 DB에 남지 않는다. (나) ui 회신(2026-11-16, main `8743fa99`): `BROKER_IP_NOT_ALLOWED` 참조 0건, `BROKER_WITHDRAWAL_ENABLED`는 문구 매핑 1줄과 테스트 1건(`entities/account/model/brokerCredentials.ts:16`, 코드가 안 오면 서버 `detail`로 폴백), DcaSkipReason 셋은 테스트 픽스처뿐이고 화면은 메타 라벨로 읽으며 모르는 값은 코드 문자열로 폴백, `BROKER_API`는 죽은 유니언 멤버다. 서버가 먼저 지워도 런타임은 깨지지 않고 배포 순서 제약이 없다. ui는 다음 타입 재생성 때 매핑 1줄·테스트 1건을 지운다(`cross-repo.md` G절). (다) `constraints.md`의 "`ErrorCode` 상수 삭제 금지"는 ui가 읽는 계약을 지키려는 규칙인데 읽는 쪽이 없어졌다. ②로 남기면 발행처 없는 상수가 메타(`dcaSkipReasons`)와 스펙에 계속 내려간다. 사용자 확인이 필요한 이유는 9-6(ui 계약 상수 삭제) 하나다. T4 배포 직전 확인은 권고 문장 그대로 둔다.
- 답: 사용자 결정(2026-11-16) ① 함께 삭제. 15의 11절 "Q-15" 항목대로 T4에서 지운다.

### Q-16 운영 정리 SQL 3건(11 D4 + EPR 완료 행)을 돌릴지
- 상태: 답함(2026-11-16 사용자 승인, 실행 완료)
- 묶음·태스크: S13 / 11 T8
- 걸린 곳: 운영 데이터를 지우는 SQL이라 로드맵 9-4에 따라 사용자 승인 뒤에만 돌린다. 읽기 전용 count는 2026-11-15에 확인했다.
  - ① 코드에 없는 스케줄러 락 행: `public.scheduler_locks` 7행(`fear-greed-12hourly`·`fear-greed-6hourly`·`fear-greed-daily`, trading-core로 옮겨 간 `market-calendar-monthly`·`market-calendar-startup`·`trading-close`·`trading-open`). 지침서에 없던 `trading.scheduler_locks`의 `ledger-reconciliation` 1행도 코드에 없다(`git grep` 0건).
  - ② 옛 단일 Flyway 이력 `public.flyway_schema_history` 22행. `flyway.md`가 "롤백 증거로 보존, 수정·삭제 금지"라 적고 있어 실행하면 그 문장을 같은 작업에서 지운다.
  - ③ EPR 완료 행: `public.event_publication` 867행, `trading.event_publication` 365행(미완료는 둘 다 0). S13에서 `completion-mode: delete`를 켰지만 설정은 이미 쌓인 행을 지우지 않는다.
- SQL(덤프 먼저: `docker exec kista-postgres pg_dump -U kista -d kistadb -t public.scheduler_locks -t trading.scheduler_locks -t public.flyway_schema_history > /tmp/s13-cleanup.sql`):
  ```sql
  DELETE FROM public.scheduler_locks WHERE name IN ('fear-greed-12hourly','fear-greed-6hourly','fear-greed-daily','market-calendar-monthly','market-calendar-startup','trading-close','trading-open'); -- 기대 7
  DELETE FROM trading.scheduler_locks WHERE name = 'ledger-reconciliation'; -- 기대 1
  DROP TABLE public.flyway_schema_history; -- 22행
  DELETE FROM public.event_publication WHERE completion_date IS NOT NULL; -- 기대 약 867(실행 시점에 다시 센다)
  DELETE FROM trading.event_publication WHERE completion_date IS NOT NULL; -- 기대 약 365
  ```
- 선택지: ① 셋 다 돌린다(③은 S13 배포 뒤에 돌려야 다시 쌓이지 않는다) ② ①·③만 돌리고 ②(옛 이력)는 남긴다 ③ 그대로 둔다(해는 없다).
- 권고: ①. 시점은 S13 배포 뒤 매매 시간대 밖(KST 07:00~16:00). 미완료 EPR 행(`completion_date IS NULL`)은 건드리지 않는다.
- 멈춘 범위: 없음. 코드 변경은 S13에 들어갔다.
- 답: ① 셋 다(2026-11-16). S13 배포(`f50ffb5d8`) 뒤 다시 센 값 그대로 한 트랜잭션에서 지웠다: 락 7·1행, `public.flyway_schema_history` drop, EPR 완료 877·365행. 덤프 `kista-server:/tmp/s13-cleanup-202611160453.sql`. `flyway.md`·`docker-infra.md`의 보존 문장을 고쳤다.

### Q-17 ui spec 재수신에서 드러난 nullable 닉네임의 표시 문구와 배포 묶음
- 상태: 자체 결정(문구는 사용자가 바꿀 수 있다)
- 묶음·태스크: 04 단계 A 후속 / ui `190caf90`(실서버 spec 재수신)
- 걸린 곳: ui가 KIS 제거 때 openapi.json을 jq로만 고쳐서, 실서버 spec과 KIS 무관한 차이가 남아 있었다. 출처는 `1915ba682`(jspecify required·nullable 정확화)다. 이 spec을 다시 받자 관리자 응답의 `nickname`·`ownerNickname`(카카오 닉네임 미동의 시 null), `AuditLogResponse.adminId`, `ErrorLogResponse.context`, `AdminAccountResponse.broker`가 `T | null`로 드러났다. ui는 이 필드들을 non-null로 다뤘다. 지침서에는 null 표시 문구가 없다.
- 근거: `AdminUserResponse.java:15` `@Nullable String nickname`. ui typecheck 오류 23건. api 세션 확인 결과 Jackson 기본값이 ALWAYS라 null은 예전부터 내려왔다. 런타임 JSON은 바뀌지 않았다.
- 선택지(문구): ① 표 셀은 copy.md 규칙대로 "-", 필터 라벨·승인/탈퇴 다이얼로그·토스트는 `ID <userId 앞 8자>`(ui `entities/admin/model/userLabel.ts`) ② 전부 "-"(다이얼로그가 "- 님을 승인합니다"가 되고 필터 후보끼리 구분되지 않는다) ③ api가 null 대신 "(알 수 없음)" 같은 문자열을 내려준다(계약 변경, ui와 같은 묶음이 필요하다).
- 선택지(묶음): `1915ba682`는 이미 운영에 배포됐다(e044e983c의 조상). ui `190caf90`은 KIS 묶음(api 세 role + ui `b33118f1`·`4f94b3dc`)과 독립이고, 따로 배포해도 된다.
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-15) 문구 ①, 묶음 독립. 닉네임 정렬은 다른 관리자 표처럼 null을 방향과 무관하게 맨 아래에 둔다. 문구를 바꾸려면 `userLabel` 한 곳과 표 셀 5곳(`AdminUsersTable`·`AdminPendingList`·`AdminAccountsTable`·`AdminTradesTable`·`AccountTable`)을 고친다.
- 검토: (Fable, 2026-11-16) 유지. 런타임 JSON은 바뀌지 않았고(`1915ba682`는 스펙 표기만 고쳤다) null 표시는 ui 소관이다. ③(api가 대체 문자열을 내려준다)은 `ui-api-principles.md`와 어긋나 기각이 맞다.

### Q-18 ui 코인 제거 뒤 api 15 T4 전까지 남는 표시 문제(리뷰 1~5번)
- 상태: 자체 결정
- 묶음·태스크: ui S1 / ui 지침서 12, ui `6a3e0a14`
- 걸린 곳: ui 12 "배포와 다른 지침서와의 의존"의 "ui 먼저". ui가 코인 분기를 지운 뒤에도 서버에는 코인 데이터와 키가 남아 있다. `/code-review high`가 그때 생기는 문제 5건을 짚었다. ① 코인 계좌 편집 화면 크래시(`CredentialsCard`가 `BROKER_CREDENTIAL[broker]`를 구조분해) ② 코인 계좌 상세·DCA 상세가 주식 포트폴리오·시세를 불러 400 ③ 금액을 USD로 표시 ④ 과거 USDT 체결(`/api/daily-trades`, 관리자 체결 표)이 `$`로 보임 ⑤ 정책 JSON의 `UPBIT`·`BINANCE` 키 때문에 관리자 설정에 토글이 보임.
- 근거: T0 운영 조회(2026-11-15, 읽기 전용)에서 `BINANCE`·`UPBIT` 계좌 live 0(total 각 1), `BTC`·`ETH` 전략 live 0, 미체결 0. 삭제 표시된 계좌는 목록에 나오지 않으므로 운영에서 ①~③은 일어나지 않는다. ④⑤만 실제로 보일 수 있다.
- 선택지: ① 그대로 두고 api 15 T4가 데이터·정책 키를 지우면 사라진다(채택) ② ui에 "지원하지 않는 증권사" 가드를 한 곳 둔다(지침서 12가 기각한 "죽은 분기 남기기"와 같다) ③ USDT 표기만 되살린다(지침서 12 목표 "통화는 KRW·USD 둘"과 충돌).
- 멈춘 범위: 없음. 영향은 api 15 T4 배포 전까지 관리자와 과거 코인 체결이 있던 사용자 1명이 받는다.
- 답: 자체 결정(2026-11-16) ①. api 15 T4는 정책 JSON의 `UPBIT`·`BINANCE` 키와 과거 USDT 체결 행을 정리 대상에 넣었는지 확인한다.
- 검토: (Fable, 2026-11-16) 유지. 확인 요청 둘 다 15에 이미 들어 있다: 정책 JSON 키는 15의 4절 3번, 과거 USDT 체결 행은 4절 2번(C2 완전 삭제, `orders` 포함). 운영 정책 JSON에는 `KIS` 키도 남아 있어 4절 3번에 더했다(15의 11절). ①~③은 운영에서 일어나지 않는다(코인 계좌 live 0, 2026-11-16 재확인).

### Q-19 ui `BrokerCode`를 생성 타입보다 좁힌 것
- 상태: 자체 결정
- 묶음·태스크: ui S1 / ui 지침서 12 T2·T4
- 걸린 곳: ui 12 "하지 말아야 할 것"의 "`shared/lib/api-types.ts`를 손으로 고치지 않는다". 생성 타입의 증권사 enum에는 서버가 지울 때까지 `UPBIT`·`BINANCE`가 남는다. 그런데 `Record<BrokerCode, …>` 꼴의 표와 관리자 설정 테스트 fixture가 코인 키를 요구했다.
- 근거: ui `shared/lib/api-schema.ts:9` `export type BrokerCode = Extract<NonNullable<components['schemas']['AccountRequest']['broker']>, 'TOSS' | 'MOCK' | 'MERITZ'>`
- 선택지: ① 수기 타입에서 `Extract`로 좁힌다(채택). 런타임 값은 문자열이라 깨지지 않는다 ② 생성 타입을 그대로 쓰고 코인 키를 남긴다(판정 grep이 0이 되지 않는다).
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-16) ①. api 15 T4 뒤 ui가 spec을 다시 받으면 `Extract`는 아무 일도 하지 않게 된다. 그때 지운다.
- 검토: (Fable, 2026-11-16) 유지. 생성 타입을 손대지 않고 수기 타입에서 좁힌 것이라 ui 12의 금지에 닿지 않는다.

### Q-20 대시보드 시장 차트의 "크립토 · 보조지표" 카테고리(IBIT 등 주식 ETF)
- 상태: 답변됨
- 묶음·태스크: ui S1 / ui 지침서 12 목표 "코인이라는 개념이 ui에 없다"
- 걸린 곳: 지침서 12는 코인 매매(업비트·바이낸스·BTC·ETH)를 지운다. 이 카테고리는 미국 주식 시장의 비트코인·이더리움 현물 ETF 시세 차트라서 그대로 뒀다. 카테고리 제목과 설명에는 "크립토"라는 말이 남아 있다.
- 근거: ui `widgets/dashboard/marketChartCategories.ts:48` `title: '크립토 · 보조지표'`, `:50` `IBIT · 비트코인 현물`. 비회원 대시보드 스크린샷(2026-11-16)에서 셋째 차트 카드로 보인다.
- 선택지: ① 그대로 둔다(주식 ETF이고, api 15 G9로 BITO·EETH도 DCA 종목에 들어온다) ② 제목만 "가상자산 ETF · 보조지표" 같은 이름으로 바꾼다 ③ 카테고리를 지운다.
- 멈춘 범위: 없음.
- 답: 사용자 결정(2026-11-16) ① 그대로 둔다. 코인 매매가 아니라 미국 주식 시장의 ETF 시세 차트다. 다시 제안하지 않는다.

### Q-21 로컬 dev 시드가 업비트·바이낸스 계좌를 계속 만든다
- 상태: 자체 결정
- 묶음·태스크: ui S1 e2e 재실행 / api 15 T4
- 걸린 곳: ui 12 T5는 ui의 시드·픽스처에서 코인을 지웠다. api `scripts/dev-seed/dev-seed.sql`은 api 소관이라 손대지 않았다(ui 로드맵 4-4 "이 세션에서 kista-api의 코드를 고치지 않는다"). 이 시드를 넣으면 로컬 ui에서 `[시드] 업비트`·`[시드] 바이낸스` 계좌가 Q-18의 ①~③ 증상을 보인다.
- 근거: api `scripts/dev-seed/dev-seed.sql:224-234`, `:321-331`. 2026-11-16 시드 적용 뒤 ui e2e는 18/18 통과했다. e2e는 MOCK·TOSS 계좌만 쓴다.
- 선택지: ① api 15 T4의 통째 삭제 범위에 dev-seed의 코인 블록을 넣는다(채택 권고) ② ui가 대신 고친다(레포 경계 위반).
- 멈춘 범위: 없음.
- 답: 자체 결정(2026-11-16) ①. api 15 T4 실행 세션이 처리한다.
- 검토: (Fable, 2026-11-16) 유지. 15 T5의 대상 목록에 `scripts/dev-seed/dev-seed.sql`의 코인 시드가 이미 있고 T5는 T4와 같은 배포다.

### Q-22 테스트만 쓰는 도메인 메서드 2개를 테스트째 지울지
- 상태: 답함(2026-11-16)
- 묶음·태스크: S13 / 13 U2
- 걸린 곳: `AccountBalance.hasSufficientDepositFor`(`AccountBalance.java:61`, 테스트 6곳, 주석 "ManualTradingService 전용"은 낡음 — Q-22(`0ffaea0d0`)에서 이미 삭제돼 해소)와 `ReverseModePosition.calcLocBuyAmount`(`ReverseModePosition.java:42`, 테스트 1곳)는 main 호출처가 0이다. 13 9절은 "그 테스트가 공개 동작으로 같은 것을 확인할 수 있으면 삭제"라 했는데 옮길 공개 동작이 없다. 판정 로직(`BUY 합계`, `quotaBuyAmount`)은 다른 공개 경로가 이미 쓰고 테스트한다.
- 선택지: ① 메서드와 그 테스트를 함께 지운다. 동작 변화 없음, 약 40줄 감소 ② 지금처럼 둔다. 죽은 메서드가 남고 낡은 주석만 고친다.
- 권고: ①. 매매 공식은 `trading-formulas.md`와 실제 호출 경로 테스트가 SSOT라 죽은 메서드의 테스트가 공식을 지키지 않는다.
- 멈춘 범위: 이 두 메서드만. 답이 없으면 ②로 둔다.
- 답: ① 권고대로(2026-11-16 사용자). 지운 테스트가 지키던 쿼터 예산 반올림은 `quotaBuyQuantity` 테스트로 다시 고정했다.

### Q-23 `TradingPriceFetcher`의 raw catch를 `tryGet`으로 바꾸지 않는다
- 상태: 답함(2026-11-20)
- 묶음·태스크: S13 / 13 U2(13 8절 표 "raw catch 1곳")
- 걸린 곳: `TradingPriceFetcher.java:177`·`:191`의 `catch (Exception e)`는 일괄 조회 실패를 종목별 단건 fallback으로 넘기고, 단건까지 실패한 종목을 `failed`에 모으는 분기다. `constraints.md`는 서비스의 raw catch를 "이벤트 발행·분기가 있을 때만" 허용하므로 규칙 위반이 아니다. `tryGet`으로 바꾸면 로그 문구·레벨과 실패 종목 수집이 달라진다.
- 선택지: ① 그대로 둔다 ② `tryGet`으로 바꾸고 실패 수집을 호출부로 옮긴다(동작 변경, 시세 경로).
- 답: 자체 결정(2026-11-16) ①. 13 8절의 "raw catch 1곳" 문장을 고쳤다. 14 T3(시세 전환, N19)에서 이 파일이 바뀌면 그때 다시 본다. T3(2026-11-20)에서 `fetchWithFallback`을 삭제해 대상이 없어졌다.
- 검토: (Fable, 2026-11-16) 유지. `fetchWithFallback`(`TradingPriceFetcher.java:170-198`)의 catch 둘은 "일괄 실패 → 단건 대체", "단건 실패 → `failed`에 모아 알림 1건"이라는 분기다. `tryGet`은 실패를 `Optional.empty()`로만 돌려줘 이 수집을 대신하지 못한다.
- 멈춘 범위: 없음.

### Q-24 S15 T2의 판정 기준을 "구 경로와 일치"에서 "공식 종가와 일치"로 바꾼다
- 상태: 답함(2026-11-20)
- 묶음·태스크: S15 / 14 T2 → N19
- 걸린 곳: 14 T2의 통과 기준은 구 경로(관리자 토스)를 정답으로 두고 새 경로(Alpaca·계좌 증권사)와 비교한다. 거래일 11-17~19 비교에서 `close+20` TQQQ가 0.03·0.04·0.01달러 어긋났고(0.02 초과 2일), 22:00 KST 배치의 `prevClose`가 크게 어긋났다(예: SOXL old 49.25 대 new 58.32).
- 대조(2026-11-20): 독립 출처(financialcontent `NQ:TQQQ` 일별 시세)의 TQQQ 종가 11-13 57.68, 11-16 53.03, 11-17 51.81, 11-18 52.44는 Alpaca SIP 일봉 종가와 전부 같다. SOXL 11-13 종가 58.32(Alpaca)는 new의 `prevClose`와 같고, old 49.25는 같은 시각 현재가 49.19에 가깝다. 즉 어긋난 쪽은 구 경로다.
- 구 경로의 원인(코드): `TossPriceApi.getPrevCloses`는 캔들 조회가 실패한 종목을 현재가로 대체하고(`needsFallback`), 실패(empty)도 `prevCloseCache`의 세션 버킷에 캐시해 그 버킷(장 시작 전) 동안 계속 현재가를 쓴다. 1일차 같은 시각에 `Toss 401 — 관리자 토큰 복구 재시도 path=/api/v1/candles` warn이 있었다. 토스 캔들을 다시 호출해 응답을 보지는 않았다(그 시각을 재현할 수 없음).
- 답: 사용자(2026-11-20) 권고대로. 새 기준은 "새 경로 값이 공식 종가와 일치"이고 TQQQ 3일·SOXL 기준일 모두 충족해 T2 통과로 판정한다. 22:00 배치의 `prevClose` 차이는 구 경로 결함으로 판정에서 뺀다. 현재가 0.5% 이내·마감+20분 확정 봉 조회는 원래 기준으로도 통과였다. N19(T3 전환)가 열린다.
- 남는 위험: 전환 전까지 구 경로 결함이 운영에 남는다. 22:00 KST 배치가 쓰는 `prevClose`가 현재가로 바뀌어 있을 수 있다.
- 멈춘 범위: 없음.

### Q-25 trading-core 테스트의 기동 클래스 — `TradingApplication`을 테스트 소스셋으로 옮긴다
- 상태: 자체 결정
- 묶음·태스크: N1 / 05 단계 M(M2·M12)
- 걸린 곳: 05 4-1 M2 "`TradingApplication` 삭제"와 14-2 M12는 trading-core 테스트가 그 뒤 어떤 `@SpringBootConfiguration`을 쓸지 정하지 않았다. trading-core 테스트 약 54개(`@SpringBootTest` 4·`@DataJpaTest` 11·`@WebMvcTest` 22·`DataJpaTestBase` 상속 27)가 상향 탐색으로 `com.kista.tradingcore.TradingApplication`을 찾는다. `ApiApplication`은 조상 패키지가 아니고 trading-core 테스트 classpath에도 없다.
- 근거: 재측정(2026-11-20) — findings/10·29, 05 4-1·14-2·15, 07에 해당 계획 없음.
- 선택지: ① `TradingApplication`을 trading-core test 소스셋으로 옮기고 trading-core main `application.yml`을 테스트 리소스로 옮긴다(참조 4파일·테스트 54개 그대로 컴파일) ② 테스트 54개를 root로 옮기고 테스트 전용 설정 클래스 ③ 54곳에 `@ContextConfiguration`.
- 답: 자체 결정(2026-11-20) ①. diff가 가장 작고 trading-core 테스트의 search_path·Flyway·EPR 스키마가 지금과 같다. 운영 컨텍스트 전체는 root `ApplicationContextLoadTest`가 검증한다. 계획 `docs/superpowers/plans/2026-11-20-05-m-merge.md`(M 브랜치) D1.

### Q-26 M4(search_path를 `public`으로 단순화)를 하지 않는다
- 상태: 자체 결정
- 묶음·태스크: N1 / 05 단계 M(M4)
- 걸린 곳: 4-1 M4 "`connection-init-sql`은 `SET search_path TO public`으로 단순화". root 테스트 SQL은 `finance, kista_ref, public`에 기대도 된다(`testing.md`), shared `application-test.yml`에 search_path 키가 없어 main 값이 테스트 값이 된다.
- 근거: main SQL은 P3로 전부 스키마 한정(가드 `NativeSqlSchemaQualifiedTest`), trading-core main에 `@Formula`·`@Subselect`·`@NamedNativeQuery`·`JdbcClient`·미한정 `scheduler_locks`/`event_publication` 0건(2026-11-20 grep), 엔티티 전부 `@Table(schema)`, EPR은 `spring.modulith.events.jdbc.schema`, 락 테이블은 `kista.scheduler-lock.table`.
- 선택지: ① root 값 유지 ② `public`으로 바꾸고 root 테스트의 미한정 SQL을 고친다.
- 답: 자체 결정(2026-11-20) ①. 운영 경로가 search_path에 기대지 않아 이득이 없고 테스트 수정만 생긴다. 계획 D2.

### Q-27 Flyway 2벌(M5)을 Flyway 빈 2개가 아니라 마이그레이터 하나로 돌린다
- 상태: 자체 결정
- 묶음·태스크: N1 / 05 단계 M(M5)
- 걸린 곳: 4-1 M5 "`Flyway` 빈을 직접 2개 정의, 각 `initMethod="migrate"`, `DataJpaTestBase`에 `@Import`". `initMethod`로 돌리면 Boot의 DB 초기화 의존 감지기가 JPA를 그 뒤로 미루지 않을 수 있고(감지 대상은 `FlywayMigrationInitializer` 타입), `DataJpaTestBase`(shared testFixtures)는 root·trading-core 클래스를 import할 수 없다.
- 선택지: ① shared `platform.persistence`에 `FlywayMigrationInitializer` 빈 하나, 전략이 `kista.flyway.migrations` 목록을 순서대로 migrate, `spring.flyway.enabled: false` ② 지침서대로 빈 2개.
- 답: 자체 결정(2026-11-20) ①. root 앱은 [root, trading], trading-core 테스트는 [trading]만 둔다. 마이그레이션 파일은 바꾸지 않는다. 빈 DB 기동으로 순서를 확인한다(계획 Task 1 Step 6·Task 6 Step 2). 계획 D3.

### Q-28 `RoleRoutes` 경로 표를 따로 만들지 않는다
- 상태: 자체 결정
- 묶음·태스크: N1 / 05 단계 M(M10)
- 걸린 곳: 1-3 "`RoleRoutes`(platform)가 기동 시 `RequestMappingHandlerMapping`에서 모듈별 경로를 모아 가드에 준다".
- 선택지: ① `RoleRoutingGuard`가 요청마다 핸들러를 찾아 핸들러 클래스가 `com.kista.tradingcore.` 패키지이거나 `@ConditionalOnWorkerRole`이면 엔진으로 본다 ② 기동 시 표를 만든다.
- 답: 자체 결정(2026-11-20) ①. 클래스가 하나 줄고 판정 기준이 1-2 표("엔진 = 지금의 trading-core")와 같다. Caddy 일치는 기존 `CaddyRoutingTest`가 계속 검증한다. 단계 R에서 모듈이 재편되면 판정 기준을 모듈 목록으로 바꾼다. 계획 D4.

### Q-29 탈퇴 cascade(미체결 주문 취소)가 web 프로세스에서 돈다
- 상태: 자체 결정(기록만)
- 묶음·태스크: N1 / 05 단계 M(M9)
- 걸린 곳: 1-1 "원장을 바꾸거나 브로커를 호출하는 코드는 worker 프로세스에서만" 대 3절 P5·14-2 M9 "스트림 경로를 지우고 T 리스너가 이벤트를 직접 받는다". 탈퇴 요청은 web이 받으므로 `TradingUserCascadeListener`(AFTER_COMMIT, 브로커 취소 호출)도 web에서 돈다.
- 선택지: ① 지침서대로(web에서 실행) ② worker로 보내는 경로를 새로 만든다.
- 답: 자체 결정(2026-11-20) ①. 지침서가 정한 설계이고 탈퇴는 드물어(활성 사용자 2명) 메리츠 호출 간격 합산·예산 락 경합이 사실상 생기지 않는다. 계획 D5. 다시 볼 조건: 단계 C(8절) 또는 탈퇴가 잦아질 때.

### Q-30 확정 종가가 없을 때 마감 리포트를 어떻게 하는가
- 상태: 자체 결정(2026-11-20)
- 묶음·태스크: N19 / 14 T3
- 걸린 곳: 마감 리포트가 마감+20분에 확정 종가를 못 얻는 날(Alpaca 지연·장애). 옛 동작은 null 종가를 그대로 저장했다.
- 선택지: ① 정상 마감 경로에서 10분 뒤 한 번 재조회하고, 그래도 없으면 그 전략은 리포트하지 않고 CLOSE를 PLACED로 두며 알림 1건. `reconcile`(매시 :05)이 closeAt+40분부터 리포트를 다시 돌린다(채택, 지침서 06 T2의 일부를 앞당김) ② null 종가 저장(옛 동작 — 체결은 기록되나 평가·롤오버가 빠진다) ③ 재기동 재개만(기각: 같은 날 22:00 개장 배치가 낡은 보유로 거래한다).
- 답: 자체 결정 ①. 알려진 한계: 보류 알림이 KST 자정까지 매시 반복되고, 보류일에도 heartbeat는 핑하며, `CloseReportScope` 창은 마감+10부터 시작한다(리포트보다 이르지만 보수적이라 유지).
- 멈춘 범위: 없음.

### Q-31 S7 T5: `description` 필드를 유지하고 원천 있는 enum만 채운다
- 상태: 자체 결정(2026-11-25)
- 묶음·태스크: S7 / 10 T5
- 걸린 곳: 지침서 2-3 "필드를 없앤다" 대 추가만 원칙. `StrategyTypeMeta`는 label==description이다.
- 답: 자체 결정. 추가만 원칙이 우선이라 `description`은 유지하고 설명 원천이 있는 enum(ETF 명칭)만 채운다. label 값은 바꾸지 않는다. 반대쪽: label을 짧게 바꾸면 ui 화면 문구가 바뀌어 ui 확인이 필요하다.

### Q-32 S7 T5: 메타 `Cache-Control`을 1시간에서 `no-store`로 바꾼다
- 상태: 자체 결정(2026-11-25)
- 묶음·태스크: S7 / 10 T5
- 답: 자체 결정. 런타임 값(활성 여부·기본값·승인 설정)이 섞여 캐시하면 낡은 값이 나간다. 반대쪽: 요청마다 DB 1회와 정책 조회(같은 프로세스 내부 HTTP, 타임아웃 3s/10s, 실패 시 기본 정책으로 강등)가 든다.

### Q-33 S7 T5: 앱셸 ArchUnit 규칙에 `RuntimeConfigService` 하나만 예외로 둔다
- 상태: 자체 결정(2026-11-25)
- 묶음·태스크: S7 / 10 T5
- 답: 자체 결정. admin `RuntimeConfigService`를 `NamedInterface("api")`로 공개하고 `HexagonalArchitectureTest`에 그 클래스만 예외로 둔다. 반대쪽: 포트 래퍼는 `interfaces_must_earn_their_keep` 위반이다.

### Q-34 S7 T6·T10·T12 판정 범위
- 상태: 자체 결정(2026-11-25)
- 묶음·태스크: S7 / 10 T6·T10·T12
- 답: ① T6 마감 리포트 창·당일 주문 반영 전 보관·재개 시 1주 미만 잔액은 판정에서 제외한다(응답 조립 때 데이터가 없다) → allowed여도 400이 날 수 있고 ui는 400 `detail`을 보인다. ② T10 `fieldErrors`는 `{field, detail}`(지침서 2-5, 초안의 `message`는 오류)이고 경고 `AVAILABLE_CASH_EXCEEDED`를 더했다. ③ T12 캔들 지표는 RAW 종가, DCA는 수정주가라 같은 코드·다른 입력이다. RSI 창은 250(`DcaIndicators.CLOSE_WINDOW`, DCA 수집과 같음).

### Q-35 S7 T10: ui에만 있는 등록 규칙 2건을 서버에 넣을지
- 상태: 결정(2026-11-25) — 사용자가 권고 (가)를 택했다. 서버에 넣지 않고 ui가 막는다
- 묶음·태스크: S7 / 10 T10(`cross-repo.md` "ui에만 있던 규칙은 서버에 먼저")
- 내용: ① 비VR 예수금 > 0 ② PRIVACY 최소 시드. 지금 서버는 INFINITE·PRIVACY 등록의 null 예수금을 0으로 저장하고(테스트 `register_infinite_nullDeposit_persistsZero`·`register_privacy_nullDeposit_persistsZero` 등 4건이 고정), DCA는 보유 수량만으로 시작할 수 있다. 최소 시드 검사는 INFINITE만 한다.
- 선택지: (가) 지금 동작 유지 — ui가 계속 막는다(검증 엔드포인트로는 우회 불가가 아니다). (나) 서버에 추가 — 기존 테스트 기대값을 바꾸고 null 예수금 등록이 400이 된다(ui는 이미 막으므로 화면 영향 없음, API 직접 호출만 바뀜).
- 권고: (가). 돈이 더 움직이지 않고 ui가 막고 있다. (나)는 PRIVACY 최소 시드 계산(기준표·가격 조회)이 등록 경로에 새 외부 의존을 더한다.
- 같이 정한 것: 평단가만 입력은 서버에 넣었다. NO_PRIVACY_BASE·VR 금액 0은 ui에 남긴다(시점 의존·폼 개념).

### Q-36 S7: API 키 만료일 직접 입력이 오늘을 받던 것
- 상태: 결정(2026-11-25, 권고대로 진행 지시)
- 내용: `AccountService.manualExpiry`가 KST 오늘을 허용해 KST 자정으로 저장했다. `ApiKeyExpiry`는 남은 일수 0을 EXPIRED로 보므로 저장 즉시 "API 키 재등록 필요"가 됐다.
- 결정: KST 내일부터 받는다. 메시지는 "API 키 만료일은 내일 이후여야 합니다."(IAE, ErrorCode 변경 없음). ui 날짜 입력 최솟값도 내일로 — `cross-repo.md` S7 후속.

## Fable 대기 작업 (2026-11-15 추가) — 2026-11-16 처리

| 항목 | 결과 | 원본 |
|---|---|---|
| F-01 S18 | 유지, 결함 없음. 날짜 계산을 Alpaca 실호출로 확인했다 | `findings/30` 1절 |
| F-02 W1 | 유지, 운영 결함 없음. 후속 4건은 15 T4·T5에 실었다. `archive/broker-kis` 태그를 로컬에 걸었다 | `findings/30` 2절, 15의 11절 |
| F-03 Q-14 | 유지 | Q-14 검토 줄 |
| F-04 N1 런북 | 순서를 "15 단계 A → M"으로 고치고 런북을 보정했다. 0단계 명령은 운영에서 시험 실행했다. M 목록 전체 재검수는 하지 않았다(15 뒤 M 브랜치 세션의 첫 작업) | 05의 15절 |
| F-05 | 할 일 없음. 11 D1~D4·12 D0~D5는 2026-11-13에 전부 답을 받았다(`PROGRESS.md` 맨 위 표). 로드맵 7절의 "사용자 결정 대기" 문장이 낡아 있어 고쳤다 | 로드맵 7절 |

아직 열려 있는 항목(2026-11-16): Q-08(사용자가 BITO·EETH 현재가 조회), Q-13(14 T3 전에 토스 시세 1회 호출 — 사용자). Q-15는 2026-11-16 답변됨(함께 삭제).

아래는 요청 원문이다.

Fable 한도가 돌아오면 한 세션에서 몰아서 한다. 위에서부터 순서대로 하고, 끝난 항목에는 결과 문서와 커밋 해시를 적는다. 검수만 하고 고치지 않는다. 고칠 결함은 로드맵에 후속 묶음으로 추가한다. 산출물은 `findings/30-post-hoc-review.md` 하나에 절로 나눈다.

### F-01 S18 사후 검수 (돈 경로, 2026-11-15 17:25 KST `f105d68db`로 배포됨 — 배포 전 검수 없이 나갔다)
- 대상: `f105d68db`(08 8절 T8~T10). 리버스 별지점 = 직전 5거래일 종가 평균(실패·5개 미만이면 원장 5행 평균 폴백, 같은 거래일 리버스 LOC 매도가 재사용), 리버스 종료 `>`.
- 볼 것: 원문(`trading-formulas.md`, 08의 원문 인용)과 코드·테스트 일치. 9-2 U4 세 가지(배포 전 저장된 진행 중 리버스 전략 행의 해석, 다음 거래일 주문, 주문 0건 경로). 폴백이 주문을 더 내거나 수량을 부풀리는 쪽인지(U3).
- 운영에 진행 중 INFINITE·리버스 전략이 있으면 읽기 전용 조회 뒤 9-4의 변경 전후 다음 거래일 주문 비교표가 필요한지 판정한다.
- 산출: 배포 가능 / 조건부 / 보류 판정, 결함 목록, 배포 전 확인표(SELECT·기대값·시각). 로드맵 S18 칸에 한 줄.

### F-02 W1(04 단계 A KIS 삭제) 사후 검수 (운영 데이터 삭제, 2026-11-15 21:51 KST `966f5e284`로 배포됨 — V22 적용 완료)
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
- 11 D1·D2·D3, 12 D0이 N12~N14·N22(13 U7, 옛 번호 N18)를 막고 있다. 지침서에 권고가 이미 있으면 건너뛴다.
