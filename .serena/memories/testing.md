# 테스트 패턴

## TradingService 테스트
- `@InjectMocks` 미사용 → `new TradingService(...)` 직접 생성자 호출
- 필드 추가 시: `@Mock` 추가 + `@BeforeEach`의 생성자 인수 추가 필수

## @WebMvcTest
- SecurityConfig 사용 시: `@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})`
- Controller에 새 UseCase 필드 추가 시: `@MockBean` 추가 필수 (누락 시 ApplicationContext 실패)
- SecurityConfig에 새 Filter 추가 시: 해당 Filter도 `@Import`에 추가 필수

## findByIdOrThrow Mockito 주의
- `findByIdOrThrow`는 interface default 메서드 → Mockito가 override
- `when(repo.findById(...))` stub 무시됨 → `when(repo.findByIdOrThrow(...))` 직접 stub 필요

## /api/internal/** 테스트 패턴
```java
@TestPropertySource(properties = "internal.api.token=test-token")
// 헤더: .header("X-Internal-Token", "test-token")
```

## Adapter 내부 중첩 타입
- `private record` 금지 → `record` (package-private)으로 선언해야 테스트에서 `Outer.Inner.class` 참조 가능
