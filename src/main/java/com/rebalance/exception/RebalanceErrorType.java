package com.rebalance.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Stable error codes; the code is sent as the problem "title" and the description as "detail".
 */
@Getter
@AllArgsConstructor
public enum RebalanceErrorType {
    RB_999("Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    RB_998("Incorrect request data: ", HttpStatus.BAD_REQUEST),
    RB_997("The request conflicts with the current state. Reload and try again.", HttpStatus.CONFLICT),

    // users
    RB_001("Email already taken", HttpStatus.CONFLICT),
    RB_002("User not found", HttpStatus.NOT_FOUND),
    RB_003("Current password is incorrect", HttpStatus.BAD_REQUEST),
    RB_004("Too many attempts. Wait a few minutes and try again.", HttpStatus.TOO_MANY_REQUESTS),

    // expenses
    RB_101("Expense not found", HttpStatus.NOT_FOUND),
    RB_102("Expense was changed by someone else. Reload and try again.", HttpStatus.CONFLICT),
    RB_103("Invalid split: ", HttpStatus.BAD_REQUEST),
    RB_104("The payer and all participants must be active members of the group", HttpStatus.BAD_REQUEST),
    RB_105("A member can participate in an expense only once", HttpStatus.BAD_REQUEST),

    // groups
    RB_201("Group not found", HttpStatus.NOT_FOUND),
    RB_202("Only the group owner can do this", HttpStatus.FORBIDDEN),
    RB_203("The member's balance must be zero first. Settle up and try again.", HttpStatus.CONFLICT),
    RB_205("This is not possible for a personal group", HttpStatus.BAD_REQUEST),
    RB_206("Invite link is invalid or disabled", HttpStatus.NOT_FOUND),
    RB_207("Member not found", HttpStatus.NOT_FOUND),
    RB_208("The owner can't be removed. Use \"Leave group\" instead.", HttpStatus.BAD_REQUEST),

    // images
    RB_301("Image not found", HttpStatus.NOT_FOUND),
    RB_302("Error saving image", HttpStatus.INTERNAL_SERVER_ERROR),
    RB_303("Invalid or unsupported image (use JPEG, PNG or GIF)", HttpStatus.BAD_REQUEST),
    RB_304("Image is too large (max 5 MB)", HttpStatus.PAYLOAD_TOO_LARGE),

    // authentication
    RB_401("Not authenticated", HttpStatus.UNAUTHORIZED),
    RB_402("Wrong email or password", HttpStatus.UNAUTHORIZED);

    private final String description;
    private final HttpStatus status;
}
