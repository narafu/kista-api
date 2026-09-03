package com.kista.finance.adapter.in.web;

import com.kista.finance.domain.model.FinanceAccount;
import com.kista.finance.domain.model.FinanceAccountCommand;
import com.kista.application.usecase.BlacklistUseCase;
import com.kista.finance.application.usecase.FinanceAccountUseCase;
import com.kista.admin.application.port.output.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FinanceAccountController.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceAccountControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean FinanceAccountUseCase accountUseCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void list_returns200() throws Exception {
        when(accountUseCase.list(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/finance/accounts")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void create_returns201WithLocationHeader() throws Exception {
        UUID savedId = UUID.randomUUID();
        FinanceAccount saved = new FinanceAccount(savedId, null, USER_ID,
                FinanceAccount.Type.SECURITIES, "토스증권 일반계좌", null, null, Instant.now());
        when(accountUseCase.create(any(), any(), any(FinanceAccountCommand.class))).thenReturn(saved);

        mockMvc.perform(post("/api/finance/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"SECURITIES\",\"name\":\"토스증권 일반계좌\"}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/finance/accounts/" + savedId));
    }

    @Test
    void create_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/finance/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"SECURITIES\",\"name\":\"토스증권 일반계좌\"}")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withNonNumericAccountNo_returns400() throws Exception {
        mockMvc.perform(post("/api/finance/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"SECURITIES\",\"name\":\"토스증권 일반계좌\",\"accountNo\":\"123-456\"}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountUseCase);
    }

    @Test
    void create_withNumericAccountNo_returns201() throws Exception {
        UUID savedId = UUID.randomUUID();
        FinanceAccount saved = new FinanceAccount(savedId, null, USER_ID,
                FinanceAccount.Type.SECURITIES, "토스증권 일반계좌", "12345678", null, Instant.now());
        when(accountUseCase.create(any(), any(), any(FinanceAccountCommand.class))).thenReturn(saved);

        mockMvc.perform(post("/api/finance/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"SECURITIES\",\"name\":\"토스증권 일반계좌\",\"accountNo\":\"12345678\"}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNo").value("12345678"));
    }

    @Test
    void update_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        FinanceAccount updated = new FinanceAccount(id, null, USER_ID,
                FinanceAccount.Type.BANK, "은행계좌(수정)", null, null, Instant.now());
        when(accountUseCase.update(any(), any(), any(FinanceAccountCommand.class))).thenReturn(updated);

        mockMvc.perform(put("/api/finance/accounts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"BANK\",\"name\":\"은행계좌(수정)\"}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("은행계좌(수정)"));
    }

    @Test
    void delete_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/finance/accounts/{id}", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isNoContent());

        verify(accountUseCase).delete(id, USER_ID);
    }

    @Test
    void share_returns200WithGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        FinanceAccount shared = new FinanceAccount(id, groupId, USER_ID,
                FinanceAccount.Type.SECURITIES, "토스증권 일반계좌", null, null, Instant.now());
        when(accountUseCase.shareToGroup(id, USER_ID)).thenReturn(shared);

        mockMvc.perform(patch("/api/finance/accounts/{id}/share", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(groupId.toString()));
    }

    @Test
    void share_notOwner_returns403() throws Exception {
        UUID id = UUID.randomUUID();
        when(accountUseCase.shareToGroup(id, USER_ID))
                .thenThrow(new SecurityException("본인 소유 계좌만 그룹에 공유할 수 있습니다"));

        mockMvc.perform(patch("/api/finance/accounts/{id}/share", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unshare_returns200WithNullGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        FinanceAccount personal = new FinanceAccount(id, null, USER_ID,
                FinanceAccount.Type.SECURITIES, "토스증권 일반계좌", null, null, Instant.now());
        when(accountUseCase.unshare(id, USER_ID)).thenReturn(personal);

        mockMvc.perform(patch("/api/finance/accounts/{id}/unshare", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(nullValue()));
    }
}
