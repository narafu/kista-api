# 코드 컨벤션

## 일반
- Lombok: `@Slf4j` + `@RequiredArgsConstructor` 표준 (수동 로거/생성자 금지)
- package-private 타입 참조 시: `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)`
- 주석: `//` 인라인만. Javadoc·블록 주석 금지
- 필드: `// 역할 한 줄` 인라인 / 비즈니스 로직 블록 직전 단계 설명 한 줄

## 도메인 모델
- 불변 record 사용. Spring·JPA 어노테이션 금지
- `TradingCycle.Ticker` (nested enum) — import: `com.kista.domain.model.tradingcycle.TradingCycle.Ticker`
- 수량 변수명: 보유잔고=`holdings`, 주문/체결=`quantity`. `qty` 사용 금지

## REST API
- URI: 명사·복수형, 계층으로 소속 표현, 동사 URI 금지
- 응답: 도메인 record 직접 반환 금지 → `XxxResponse.from(domain)` DTO
- 생성 201: `Location` 헤더 포함
- userId 추출: `@AuthenticationPrincipal UUID userId` (SecurityContextHolder 금지)
- 소유권 검증: `account.verifyOwnedBy(requesterId)` → SecurityException → Controller에서 403
- GlobalExceptionHandler: NoSuchElementException→404, IllegalArgumentException→400 자동 처리

## JPA
- `@ManyToOne`에 `@JoinColumn` 항상 명시
- Enum 매핑: `@Enumerated(EnumType.STRING)` + VARCHAR(20). PostgreSQL 네이티브 ENUM 금지
- AES-256 암호화 컬럼: `length=512` 이상 필수
- `@EnableJpaAuditing`: `adapter/out/persistence/JpaAuditingConfig.java`에만 (SpringBootApplication 금지)
- `@SQLRestriction("deleted_at IS NULL")`: 소프트 삭제. nativeQuery에서는 수동 추가 필수

## Security/Filter
- `@Component` Filter를 SecurityFilterChain에 추가 시 `FilterRegistrationBean.setEnabled(false)` 필수 (이중 등록 방지)
- `JwtAuthFilter` catch: `Exception`으로 (JwtException만 잡으면 NPE 미처리 → 403)

## 트랜잭션
- `@Transactional` 내 외부 시스템 호출 금지 → `@TransactionalEventListener(AFTER_COMMIT)` 패턴

## 날짜 변환
- 도메인 `tradeDate`: KST 일자 / DB `trade_date`: UTC(-1일) 일자
- `TradeDateConverter.toUtc()` / `toKst()` 경유 필수. 인라인 `minusDays(1)` 직접 사용 금지
