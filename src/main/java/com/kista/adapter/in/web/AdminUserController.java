package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminRoleRequest;
import com.kista.adapter.in.web.dto.AdminStatusRequest;
import com.kista.adapter.in.web.dto.AdminUserResponse;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminUserUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserUseCase adminUser; // 사용자 조회 + 액션 (승인/거절/역할변경/삭제)

    // 전체 또는 상태별 사용자 목록 조회
    @Operation(summary = "사용자 목록 조회")
    @GetMapping
    public List<AdminUserResponse> listUsers(
            @RequestParam(required = false) User.UserStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal UUID adminId) {
        List<AdminUserView> views = status == null
                ? adminUser.listAll(from, to)
                : adminUser.listByStatus(status, from, to);
        return AdminUserResponse.fromList(views);
    }

    // 사용자 상태 변경 — ACTIVE(승인) / REJECTED(거절)
    @Operation(summary = "사용자 상태 변경", description = "status: ACTIVE(승인), REJECTED(거절)")
    @PatchMapping("/{userId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateStatus(@PathVariable UUID userId,
                             @Valid @RequestBody AdminStatusRequest body,
                             @AuthenticationPrincipal UUID adminId) {
        switch (body.status()) {
            case ACTIVE   -> adminUser.approveUser(adminId, userId);
            case REJECTED -> adminUser.rejectUser(adminId, userId, body.reason());
            default -> throw new IllegalArgumentException("허용되지 않는 status: " + body.status());
        }
    }

    // 역할 변경 (USER ↔ ADMIN)
    @Operation(summary = "역할 변경")
    @PatchMapping("/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@PathVariable UUID userId,
                           @RequestBody AdminRoleRequest body,
                           @AuthenticationPrincipal UUID adminId) {
        adminUser.changeRole(adminId, userId, body.role());
    }

    // 사용자 삭제
    @Operation(summary = "사용자 삭제")
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID userId, @AuthenticationPrincipal UUID adminId) {
        adminUser.deleteUser(adminId, userId);
    }

}
