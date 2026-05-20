package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminUserResponse;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminListUsersUseCase;
import com.kista.domain.port.in.AdminUserActionUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Tag(name = "Admin - Users", description = "관리자 사용자 관리 API")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminListUsersUseCase listUsers;   // 사용자 목록 조회
    private final AdminUserActionUseCase userAction; // 사용자 액션 (승인/거절/역할변경/삭제)

    // 전체 또는 상태별 사용자 목록 조회
    @Operation(summary = "사용자 목록 조회")
    @GetMapping
    public List<AdminUserResponse> listUsers(
            @RequestParam(required = false) User.UserStatus status,
            @AuthenticationPrincipal UUID adminId) {
        List<User> users = status == null
                ? listUsers.listAll()
                : listUsers.listByStatus(status);
        return AdminUserResponse.fromList(users);
    }

    // 사용자 승인
    @Operation(summary = "사용자 승인")
    @PostMapping("/{userId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approveUser(@PathVariable UUID userId, @AuthenticationPrincipal UUID adminId) {
        try {
            userAction.approveUser(adminId, userId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 사용자 거절
    @Operation(summary = "사용자 거절")
    @PostMapping("/{userId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejectUser(@PathVariable UUID userId, @AuthenticationPrincipal UUID adminId) {
        try {
            userAction.rejectUser(adminId, userId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 역할 변경 (USER ↔ ADMIN)
    @Operation(summary = "역할 변경")
    @PatchMapping("/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@PathVariable UUID userId,
                           @RequestBody RoleRequest body,
                           @AuthenticationPrincipal UUID adminId) {
        try {
            userAction.changeRole(adminId, userId, body.role());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 사용자 삭제
    @Operation(summary = "사용자 삭제")
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID userId, @AuthenticationPrincipal UUID adminId) {
        try {
            userAction.deleteUser(adminId, userId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    record RoleRequest(User.UserRole role) {} // 역할 변경 요청 body
}
