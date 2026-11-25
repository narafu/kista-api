package com.kista.api.user.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 닉네임 변경 요청 body
public record NicknameRequest(
    @Schema(description = "변경할 닉네임 — 한글·영문·숫자·공백 1~10자", example = "나라부")
    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(max = 10, message = "닉네임은 10자 이내여야 합니다.")
    @Pattern(regexp = "^[\\p{L}\\d ]{1,10}$", message = "닉네임은 한글·영문·숫자·공백으로 1자 이상 10자 이하여야 합니다.")
    String nickname
) {}
