package com.kista.shared.platform.role;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Set;

// kista.role 값(web|worker|all)을 읽어 worker·all이면 match — 누락·오타는 기동 실패(운영 role 오설정 방지)
class WorkerRoleCondition implements Condition {

    private static final String PROPERTY = "kista.role";
    private static final Set<String> ROLES = Set.of("web", "worker", "all");

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String role = context.getEnvironment().getProperty(PROPERTY);
        // 값이 없거나 허용 값 밖이면 예외
        if (role == null || !ROLES.contains(role.trim())) {
            throw new IllegalStateException(PROPERTY + " 설정이 없거나 올바르지 않습니다. web·worker·all 중 하나를 지정해야 합니다.");
        }
        return !role.trim().equals("web");
    }
}
