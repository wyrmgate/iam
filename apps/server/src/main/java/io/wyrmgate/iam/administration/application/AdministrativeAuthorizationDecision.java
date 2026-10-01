package io.wyrmgate.iam.administration.application;

import io.wyrmgate.iam.administration.domain.AdministrativeAuthoritySource;

/** Stable result of one IAM control-plane authorization evaluation. */
public record AdministrativeAuthorizationDecision(
        boolean allowed,
        String code,
        AdministrativeAuthoritySource authoritySource) {

    public AdministrativeAuthorizationDecision {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (allowed && authoritySource == null) {
            throw new IllegalArgumentException(
                    "allowed decision requires an authoritySource");
        }
        if (!allowed && authoritySource != null) {
            throw new IllegalArgumentException(
                    "denied decision must not carry an authoritySource");
        }
    }

    public AdministrativeAuthorizationDecision(boolean allowed, String code) {
        this(
                allowed,
                code,
                allowed ? AdministrativeAuthoritySource.DIRECT_GRANT : null);
    }

    public static AdministrativeAuthorizationDecision allow(
            AdministrativeAuthoritySource source) {
        return new AdministrativeAuthorizationDecision(
                true, "allowed", source);
    }

    public static AdministrativeAuthorizationDecision allow() {
        return allow(AdministrativeAuthoritySource.DIRECT_GRANT);
    }

    public static AdministrativeAuthorizationDecision deny(String code) {
        return new AdministrativeAuthorizationDecision(false, code, null);
    }
}
