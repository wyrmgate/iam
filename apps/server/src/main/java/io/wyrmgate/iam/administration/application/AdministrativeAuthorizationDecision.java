package io.wyrmgate.iam.administration.application;

/** Stable result of one IAM control-plane authorization evaluation. */
public record AdministrativeAuthorizationDecision(boolean allowed, String code) {

    public AdministrativeAuthorizationDecision {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }

    public static AdministrativeAuthorizationDecision allow() {
        return new AdministrativeAuthorizationDecision(true, "allowed");
    }

    public static AdministrativeAuthorizationDecision deny(String code) {
        return new AdministrativeAuthorizationDecision(false, code);
    }
}
