package com.iminiapp.backend.identity.application;

/** Shared policy for bootstrap, account creation and password reset. */
final class AdminPasswordPolicy {
    private AdminPasswordPolicy() { }

    static boolean isStrong(String password) {
        return password != null
                && password.length() >= 12
                && password.length() <= 128
                && password.matches(".*[A-Za-z].*")
                && password.matches(".*\\d.*")
                && password.chars().distinct().count() >= 4;
    }
}
