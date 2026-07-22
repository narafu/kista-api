package com.kista.adapter.in.web;

import com.kista.domain.port.out.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SseAsyncExceptionHandlingTest {

    @Test
    void asyncRequestTimeout_after_committed_sse_is_handled_without_serialization_error() throws Exception {
        assertAsyncLifecycleErrorIsHandledWithoutResponseBody(new AsyncRequestTimeoutException());
    }

    @Test
    void asyncRequestNotUsable_after_committed_sse_is_handled_without_serialization_error() throws Exception {
        assertAsyncLifecycleErrorIsHandledWithoutResponseBody(
                new AsyncRequestNotUsableException("disconnected"));
    }

    @Test
    void asyncRequestTimeout_before_first_send_sets_503_without_body() throws Exception {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        TestSseController controller = new TestSseController();
        MockMvc mockMvc = standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(appErrorLogPort))
                .build();

        // SseEmitterRegistry.connect() 재현 — emitter.send() 호출 없이 반환하므로 응답이 아직 커밋되지 않는다.
        MvcResult result = mockMvc.perform(get("/test/sse-uncommitted"))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(result.getResponse().isCommitted()).isFalse();

        AsyncRequestTimeoutException exception = new AsyncRequestTimeoutException();
        controller.uncommittedEmitter.completeWithError(exception);

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertThat(dispatched.getResolvedException()).isSameAs(exception);
        assertThat(dispatched.getResponse().getContentAsString()).isEmpty();
        verifyNoInteractions(appErrorLogPort);
    }

    private static void assertAsyncLifecycleErrorIsHandledWithoutResponseBody(Exception exception) throws Exception {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        TestSseController controller = new TestSseController();
        MockMvc mockMvc = standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(appErrorLogPort))
                .build();

        MvcResult result = mockMvc.perform(get("/test/sse"))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(result.getResponse().isCommitted()).isTrue();

        controller.emitter.completeWithError(exception);

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        assertThat(dispatched.getResolvedException()).isSameAs(exception);
        assertThat(dispatched.getResponse().getContentAsString())
                .isEqualTo("event:ping\ndata:connected\n\n")
                .doesNotContain("problem+json")
                .doesNotContain("Internal Server Error");
        verifyNoInteractions(appErrorLogPort);
    }

    @Controller
    static class TestSseController {
        private SseEmitter emitter;
        private SseEmitter uncommittedEmitter;

        @GetMapping(value = "/test/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() throws IOException {
            emitter = new SseEmitter();
            // 연결 직후 ping 전송으로 이미 커밋된 SSE 응답을 재현한다.
            emitter.send(SseEmitter.event().name("ping").data("connected"));
            return emitter;
        }

        @GetMapping(value = "/test/sse-uncommitted", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter streamUncommitted() {
            uncommittedEmitter = new SseEmitter();
            // SseEmitterRegistry.connect()와 동일하게 최초 send() 없이 반환 — 응답이 커밋되지 않은 상태를 재현한다.
            return uncommittedEmitter;
        }
    }
}
