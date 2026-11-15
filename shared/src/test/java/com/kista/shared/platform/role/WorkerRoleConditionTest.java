package com.kista.shared.platform.role;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerRoleConditionTest {

    private boolean matches(MockEnvironment env) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);
        return new WorkerRoleCondition().matches(context, null);
    }

    @Test
    void web은_매칭되지_않고_worker_all은_매칭된다() {
        assertThat(matches(new MockEnvironment().withProperty("kista.role", "web"))).isFalse();
        assertThat(matches(new MockEnvironment().withProperty("kista.role", "worker"))).isTrue();
        assertThat(matches(new MockEnvironment().withProperty("kista.role", "all"))).isTrue();
    }

    @Test
    void 누락_빈값_오타는_예외() {
        assertThatThrownBy(() -> matches(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> matches(new MockEnvironment().withProperty("kista.role", " ")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> matches(new MockEnvironment().withProperty("kista.role", "wroker")))
                .isInstanceOf(IllegalStateException.class);
    }
}
