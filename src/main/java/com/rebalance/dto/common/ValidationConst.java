package com.rebalance.dto.common;

public final class ValidationConst {
    private ValidationConst() {
    }

    public static final String passwordRegex = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[^A-Za-z0-9]).{8,255}$";
    public static final String passwordMessage = "Password should be 8-255 characters with at least one digit, one lowercase letter, one uppercase letter and one special character";
    public static final String currencyRegex = "^[A-Z]{3}$"; // ISO 4217
    public static final String currencyMessage = "Currency should be an ISO 4217 code like EUR or PLN";
    public static final String maxAmount = "999999999.99";
}
