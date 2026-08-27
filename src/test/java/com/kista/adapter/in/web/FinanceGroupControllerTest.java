package com.kista.adapter.in.web;

import com.kista.domain.model.finance.FinanceGroup;
import com.kista.domain.model.finance.FinanceGroupInvitation;
import com.kista.domain.model.finance.FinanceGroupMember;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.FinanceGroupUseCase;
import com.kista.domain.port.in.UserUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FinanceGroupController.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceGroupControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean FinanceGroupUseCase groupUseCase;
    @MockitoBean UserUseCase userUseCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void listMyGroups_returns200() throws Exception {
        FinanceGroup group = new FinanceGroup(UUID.randomUUID(), USER_ID, Instant.now());
        when(groupUseCase.listMyGroups(USER_ID)).thenReturn(List.of(group));

        mockMvc.perform(get("/api/finance/groups")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("가계부"));
    }

    @Test
    void listMembers_asMember_returns200() throws Exception {
        UUID groupId = UUID.randomUUID();
        FinanceGroupMember member = new FinanceGroupMember(UUID.randomUUID(), groupId, USER_ID,
                FinanceGroup.MemberRole.OWNER, Instant.now(), Instant.now());
        when(groupUseCase.listMembers(groupId, USER_ID)).thenReturn(List.of(member));
        User user = new User(USER_ID, "kakao", "홍길동", null, User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, null, User.DEFAULT_CHANNEL);
        when(userUseCase.getById(USER_ID)).thenReturn(user);

        mockMvc.perform(get("/api/finance/groups/{id}/members", groupId)
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$[0].nickname").value("홍길동"))
                .andExpect(jsonPath("$[0].role").value("OWNER"));
    }

    @Test
    void listMembers_multipleMembers_looksUpEachOwnNickname() throws Exception {
        UUID groupId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        FinanceGroupMember owner = new FinanceGroupMember(UUID.randomUUID(), groupId, USER_ID,
                FinanceGroup.MemberRole.OWNER, Instant.now(), Instant.now());
        FinanceGroupMember other = new FinanceGroupMember(UUID.randomUUID(), groupId, otherUserId,
                FinanceGroup.MemberRole.MEMBER, Instant.now(), Instant.now());
        when(groupUseCase.listMembers(groupId, USER_ID)).thenReturn(List.of(owner, other));
        when(userUseCase.getById(USER_ID)).thenReturn(new User(USER_ID, "kakao", "홍길동", null,
                User.UserStatus.ACTIVE, User.UserRole.USER, null, null, null, null, null, User.DEFAULT_CHANNEL));
        when(userUseCase.getById(otherUserId)).thenReturn(new User(otherUserId, "kakao2", "김철수", null,
                User.UserStatus.ACTIVE, User.UserRole.USER, null, null, null, null, null, User.DEFAULT_CHANNEL));

        mockMvc.perform(get("/api/finance/groups/{id}/members", groupId)
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$[0].nickname").value("홍길동"))
                .andExpect(jsonPath("$[1].userId").value(otherUserId.toString()))
                .andExpect(jsonPath("$[1].nickname").value("김철수"));
    }

    @Test
    void listMembers_memberDeletedMidRequest_omitsNicknameInsteadOf404() throws Exception {
        UUID groupId = UUID.randomUUID();
        FinanceGroupMember member = new FinanceGroupMember(UUID.randomUUID(), groupId, USER_ID,
                FinanceGroup.MemberRole.OWNER, Instant.now(), Instant.now());
        when(groupUseCase.listMembers(groupId, USER_ID)).thenReturn(List.of(member));
        when(userUseCase.getById(USER_ID)).thenThrow(new java.util.NoSuchElementException("사용자를 찾을 수 없습니다"));

        mockMvc.perform(get("/api/finance/groups/{id}/members", groupId)
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$[0].nickname").doesNotExist());
    }

    // 컨트롤러가 예외를 삼키지 않고 GlobalExceptionHandler(@RestControllerAdvice)가 SecurityException을
    // 403으로 매핑한다 — @WebMvcTest 슬라이스는 @RestControllerAdvice 빈을 기본으로 로드하므로 별도 @Import 불필요.
    @Test
    void listMembers_nonMember_returns403() throws Exception {
        UUID groupId = UUID.randomUUID();
        when(groupUseCase.listMembers(groupId, USER_ID))
                .thenThrow(new SecurityException("그룹 멤버가 아닙니다"));

        mockMvc.perform(get("/api/finance/groups/{id}/members", groupId)
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void invite_returns201WithLocationHeader() throws Exception {
        UUID groupId = UUID.randomUUID();
        String code = "abc123code";
        FinanceGroupInvitation invitation = new FinanceGroupInvitation(UUID.randomUUID(), groupId, USER_ID,
                null, code, FinanceGroupInvitation.Status.PENDING, Instant.now().plusSeconds(3600), Instant.now());
        when(groupUseCase.invite(eq(groupId), eq(USER_ID), eq(72L))).thenReturn(invitation);

        mockMvc.perform(post("/api/finance/groups/{id}/invitations", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInHours\":72}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/finance/invitations/" + code));
    }

    @Test
    void invite_withoutCsrf_returns403() throws Exception {
        UUID groupId = UUID.randomUUID();

        mockMvc.perform(post("/api/finance/groups/{id}/invitations", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInHours\":72}")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void respondToInvitation_patch_returns200() throws Exception {
        String code = "abc123code";
        UUID groupId = UUID.randomUUID();
        FinanceGroupInvitation accepted = new FinanceGroupInvitation(UUID.randomUUID(), groupId, UUID.randomUUID(),
                USER_ID, code, FinanceGroupInvitation.Status.ACCEPTED, Instant.now().plusSeconds(3600), Instant.now());
        when(groupUseCase.respondToInvitation(eq(code), eq(USER_ID), eq(FinanceGroupInvitation.Status.ACCEPTED)))
                .thenReturn(accepted);
        FinanceGroup group = new FinanceGroup(groupId, UUID.randomUUID(), Instant.now());
        when(groupUseCase.listMyGroups(USER_ID)).thenReturn(List.of(group));

        mockMvc.perform(patch("/api/finance/invitations/{code}", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\"}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(groupId.toString()))
                .andExpect(jsonPath("$.name").value("가계부"));
    }

    @Test
    void leaveGroup_returns204() throws Exception {
        UUID groupId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        mockMvc.perform(delete("/api/finance/groups/{id}/members/{userId}", groupId, targetUserId)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isNoContent());

        verify(groupUseCase).leaveGroup(groupId, USER_ID, targetUserId);
    }
}
