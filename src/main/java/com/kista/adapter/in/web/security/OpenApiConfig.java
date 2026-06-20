package com.kista.adapter.in.web.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("KISTA API").version("v1"))
                // 모든 엔드포인트에 Bearer JWT 인증 요구
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .in(SecurityScheme.In.HEADER)
                                        .name("Authorization")))
                // Swagger UI 태그 표시 순서 — [DEV] 맨 위, Admin 맨 아래
                .tags(List.of(
                        new Tag().name("[DEV] 개발 도구").description("로컬 프로파일 전용 — 운영 환경에서는 노출되지 않음"),
                        new Tag().name("인증").description("카카오 OAuth 로그인, 사용자 정보 조회, 승인 신청"),
                        new Tag().name("계좌").description("계좌 등록·조회·수정·삭제"),
                        new Tag().name("거래 사이클").description("계좌별 매매 사이클 등록·조회·수정·삭제·중지·재개"),
                        new Tag().name("대시보드").description("포트폴리오 스냅샷·사이클 이력 조회"),
                        new Tag().name("통계").description("계좌별 손익·체결·잔고·증거금·현재가 조회 (KIS/Toss 브로커 자동 분기)"),
                        new Tag().name("메타").description("UI 렌더링용 enum 메타데이터 (라벨, 설명, 유효값 목록)"),
                        new Tag().name("설정").description("텔레그램 봇 알림 설정 관리"),
                        new Tag().name("FCM").description("FCM 디바이스 토큰 관리"),
                        new Tag().name("PRIVACY 매매표").description("PRIVACY 전략 기준 매매표 조회"),
                        new Tag().name("Trade Stream").description("실시간 매매 알림 SSE"),
                        new Tag().name("내부 API").description("서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)"),
                        new Tag().name("Admin").description("관리자 API")
                ));
    }
}
