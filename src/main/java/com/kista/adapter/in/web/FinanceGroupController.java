package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.FinanceGroupInvitationRequest;
import com.kista.adapter.in.web.dto.FinanceGroupInvitationResponse;
import com.kista.adapter.in.web.dto.FinanceGroupInvitationStatusRequest;
import com.kista.adapter.in.web.dto.FinanceGroupMemberResponse;
import com.kista.adapter.in.web.dto.FinanceGroupResponse;
import com.kista.domain.model.finance.FinanceGroupInvitation;
import com.kista.domain.port.in.FinanceGroupUseCase;
import com.kista.domain.port.in.UserUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Tag(name = "재무")
@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class FinanceGroupController {

    private final FinanceGroupUseCase groupUseCase;
    private final UserUseCase userUseCase;

    @Operation(summary = "내 그룹 목록 조회", description = "내가 속한 그룹 전체 — 개인 그룹 포함.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/groups")
    public List<FinanceGroupResponse> listMyGroups(@AuthenticationPrincipal UUID userId) {
        return groupUseCase.listMyGroups(userId).stream()
                .map(FinanceGroupResponse::from)
                .toList();
    }

    @Operation(summary = "그룹 멤버 목록 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "그룹 멤버가 아님")
    })
    @GetMapping("/groups/{id}/members")
    public List<FinanceGroupMemberResponse> listMembers(
            @Parameter(description = "그룹 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        return groupUseCase.listMembers(id, userId).stream()
                .map(m -> FinanceGroupMemberResponse.from(m, resolveNickname(m.userId())))
                .toList();
    }

    // 조회와 탈퇴 사이 경합으로 멤버가 방금 회원 탈퇴했으면 그 한 명만 닉네임 없이 보여준다 —
    // NoSuchElementException을 그대로 전파하면 목록 전체가 404로 죽는다.
    private String resolveNickname(UUID userId) {
        try {
            return userUseCase.getById(userId).nickname();
        } catch (java.util.NoSuchElementException e) {
            return null;
        }
    }

    @Operation(summary = "그룹 초대 생성", description = "그룹 OWNER만 생성할 수 있습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공"),
            @ApiResponse(responseCode = "403", description = "그룹 OWNER가 아님")
    })
    @PostMapping("/groups/{id}/invitations")
    public ResponseEntity<FinanceGroupInvitationResponse> invite(
            @Parameter(description = "그룹 ID") @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody FinanceGroupInvitationRequest request) {
        var invitation = groupUseCase.invite(id, userId, request.expiresInHours());
        return ResponseEntity.created(URI.create("/api/finance/invitations/" + invitation.code()))
                .body(FinanceGroupInvitationResponse.from(invitation));
    }

    @Operation(summary = "그룹 초대 수락/거절")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "404", description = "초대를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "만료되었거나 이미 처리된 초대")
    })
    @PatchMapping("/invitations/{code}")
    public FinanceGroupResponse respondToInvitation(
            @Parameter(description = "초대 코드") @PathVariable String code,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody FinanceGroupInvitationStatusRequest request) {
        FinanceGroupInvitation invitation = groupUseCase.respondToInvitation(code, userId, request.status());
        return groupUseCase.listMyGroups(userId).stream()
                .filter(g -> g.id().equals(invitation.groupId()))
                .findFirst()
                .map(FinanceGroupResponse::from)
                .orElseGet(() -> new FinanceGroupResponse(invitation.groupId(), null, false));
    }

    @Operation(summary = "그룹 탈퇴 / 멤버 추방", description = "본인이 나가거나(userId=본인) OWNER가 다른 멤버를 추방할 수 있습니다. 개인 그룹은 탈퇴할 수 없습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "탈퇴 성공"),
            @ApiResponse(responseCode = "403", description = "본인이거나 OWNER가 아님"),
            @ApiResponse(responseCode = "409", description = "개인 그룹은 탈퇴할 수 없음")
    })
    @DeleteMapping("/groups/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveGroup(
            @Parameter(description = "그룹 ID") @PathVariable UUID id,
            @Parameter(description = "탈퇴 대상 사용자 ID") @PathVariable UUID userId,
            @AuthenticationPrincipal UUID requesterId) {
        groupUseCase.leaveGroup(id, requesterId, userId);
    }
}
