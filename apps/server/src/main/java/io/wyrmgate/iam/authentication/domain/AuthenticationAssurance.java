package io.wyrmgate.iam.authentication.domain;

/** Provider-neutral assurance established by Authentication and consumable by authorization adapters. */
public enum AuthenticationAssurance {
    BASELINE,
    STRONG
}