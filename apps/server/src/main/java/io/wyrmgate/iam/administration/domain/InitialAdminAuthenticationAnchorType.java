package io.wyrmgate.iam.administration.domain;

/** Authentication source recorded by the immutable initial-administrator bootstrap marker. */
public enum InitialAdminAuthenticationAnchorType {
    EXTERNAL_SUBJECT,
    FIRST_PARTY_PRINCIPAL
}
