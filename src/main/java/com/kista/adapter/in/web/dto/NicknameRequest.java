package com.kista.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 닉네임 변경 요청 body
public record NicknameRequest(
    @NotBlank
    @Size(max = 10, message = "닉네임은 10자 이내여야 합니다")
    @Pattern(regexp = "^[\\p{L}\\d ]{1,10}$", message = "한글·영문·숫자·공백 1~10자")
    String nickname
) {}
