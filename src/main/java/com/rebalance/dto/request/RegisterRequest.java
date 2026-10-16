package com.rebalance.dto.request;

import com.rebalance.dto.common.ValidationConst;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email is invalid")
        @Size(max = 254, message = "Email is too long")
        String email,
        @NotNull(message = "Password is required")
        @Pattern(regexp = ValidationConst.passwordRegex, message = ValidationConst.passwordMessage)
        String password,
        @NotBlank(message = "Nickname is required")
        @Size(max = 64, message = "Nickname should be at most 64 characters")
        String nickname,
        @NotNull(message = "Currency is required")
        @Pattern(regexp = ValidationConst.currencyRegex, message = ValidationConst.currencyMessage)
        String currency
) {
}
