package com.kista.shared.platform.role;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

// kista.role 값(web|worker|all)을 읽어 worker·all이면 match — 누락·오타는 기동 실패(운영 role 오설정 방지)
class WorkerRoleCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return KistaRole.parse(context.getEnvironment().getProperty(KistaRole.PROPERTY)) != KistaRole.WEB;
    }
}
