package io.wyrmgate.iam.administration.domain;

/** Typed source of authority for one successful control-plane authorization decision. */
public enum AdministrativeAuthoritySource {
    DIRECT_GRANT,
    DELEGATION,
    ELEVATION,
    BREAK_GLASS
}
