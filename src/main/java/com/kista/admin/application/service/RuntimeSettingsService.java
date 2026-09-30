package com.kista.admin.application.service;

import com.kista.admin.application.port.output.RuntimeSettingsPort;
import com.kista.admin.domain.model.RuntimeSettings;
import com.kista.user.application.event.ApprovalRequirementDisabledEvent;
import com.kista.user.application.port.output.ApprovalPolicyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// root 소유 런타임 설정(가입 승인·벤치마크)의 트랜잭션 경계 — 승인 판정(ApprovalPolicyPort)과 관리자 변경이 같은 행 잠금을 공유한다.
// 증권사 등록 허용·전략 생성 정책은 trading-core 소유라 여기 없다(AdminSettingsService가 TradingPolicyPort로 위임).
@Service
@RequiredArgsConstructor
@Transactional
class RuntimeSettingsService implements ApprovalPolicyPort {

    private final RuntimeSettingsPort settingsPort; // root 런타임 설정 영속화 포트
    private final ApplicationEventPublisher eventPublisher; // 트랜잭션 커밋 후 이벤트 발행용

    // 갱신 결과 — 감사 로그 diff 계산용으로 이전·저장 값을 함께 돌려준다
    record Updated(RuntimeSettings previous, RuntimeSettings saved) {}

    @Transactional(readOnly = true)
    public RuntimeSettings load() {
        return settingsPort.load();
    }

    @Override
    @Transactional
    public boolean approvalRequiredForUpdate() {
        return settingsPort.loadForUpdate().approvalRequired();
    }

    // benchmarksProvided=false면 요청에서 benchmarks가 생략된 것으로 보고 기존 값을 유지한다(문서화된 예외 규칙).
    // 요청 도메인 변환 단계에서 null이 이미 기본값으로 치환되므로 컨트롤러가 전달한 플래그로만 판별 가능하다.
    public Updated update(RuntimeSettings requested, boolean benchmarksProvided) {
        RuntimeSettings previous = settingsPort.loadForUpdate();
        RuntimeSettings effective = benchmarksProvided ? requested : requested.withBenchmarks(previous.benchmarks());
        RuntimeSettings saved = settingsPort.save(effective);
        // 승인 설정을 끄는 순간 PENDING 사용자 일괄 승인이 필요 — admin↔user 빈 순환을 피하려
        // UserUseCase를 직접 호출하지 않고 커밋 후 이벤트로 위임한다(user 모듈이 구독해 처리).
        if (previous.approvalRequired() && !saved.approvalRequired()) {
            eventPublisher.publishEvent(new ApprovalRequirementDisabledEvent());
        }
        return new Updated(previous, saved);
    }
}
