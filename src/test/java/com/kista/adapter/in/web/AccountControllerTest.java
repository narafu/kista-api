package com.kista.adapter.in.web;

import com.kista.domain.port.in.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AccountControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean RegisterAccountUseCase registerAccount;
    @MockBean UpdateAccountUseCase updateAccount;
    @MockBean DeleteAccountUseCase deleteAccount;
    @MockBean GetAccountUseCase getAccount;
    @MockBean PauseStrategyUseCase pauseStrategy;
    @MockBean ResumeStrategyUseCase resumeStrategy;

    private final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    // SupabaseJwtFilter와 동일하게 principal을 UUID로 설정
    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(UUID.fromString(USER_ID), null, List.of());
    }

    @Test
    void pause_returns_204_on_success() throws Exception {
        doNothing().when(pauseStrategy).pause(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/pause")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent());
    }

    @Test
    void resume_returns_204_on_success() throws Exception {
        doNothing().when(resumeStrategy).resume(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/resume")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent());
    }

    @Test
    void pause_returns_403_when_not_owner() throws Exception {
        doThrow(new SecurityException("접근 불가")).when(pauseStrategy).pause(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/pause")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void resume_returns_403_when_not_owner() throws Exception {
        doThrow(new SecurityException("접근 불가")).when(resumeStrategy).resume(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/resume")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void pause_returns_404_when_account_not_found() throws Exception {
        doThrow(new NoSuchElementException("없음")).when(pauseStrategy).pause(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/pause")
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNotFound());
    }
}
