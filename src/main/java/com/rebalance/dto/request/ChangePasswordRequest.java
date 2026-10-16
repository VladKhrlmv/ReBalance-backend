package com.rebalance.dto.request;

import com.rebalance.dto.common.ValidationConst;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        String currentPassword,
        @NotNull(message = "New password is required")
        @Pattern(regexp = ValidationConst.passwordRegex, message = ValidationConst.passwordMessage)
        String newPassword
) {
}
