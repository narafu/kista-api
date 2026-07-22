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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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

    private static void assertAsyncLifecycleErrorIsHandledWithoutResponseBody(Exception exception) throws Exception {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        TestSseController controller = new TestSseController();
        MockMvc mockMvc = standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(appErrorLogPort))
                .build();

        MvcResult result = mockMvc.perform(get("/test/sse"))
                .andExpect(request().asyncStarted())
                .andReturn();

        controller.emitter.completeWithError(exception);

        mockMvc.perform(asyncDispatch(result));

        verifyNoInteractions(appErrorLogPort);
    }

    @Controller
    static class TestSseController {
        private SseEmitter emitter;

        @GetMapping(value = "/test/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() throws IOException {
            emitter = new SseEmitter();
            // 연결 직후 ping 전송으로 이미 커밋된 SSE 응답을 재현한다.
            emitter.send(SseEmitter.event().name("ping").data("connected"));
            return emitter;
        }
    }
}
