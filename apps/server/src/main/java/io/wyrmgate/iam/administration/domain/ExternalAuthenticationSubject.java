package io.wyrmgate.iam.administration.domain;

import java.net.URI;

/** Stable external authentication subject after cryptographic token validation. */
public record ExternalAuthenticationSubject(String issuer, String subject) {

    private static final int MAX_COMPONENT_LENGTH = 512;

    public ExternalAuthenticationSubject {
        issuer = requireExactText(issuer, "issuer");
        subject = requireExactText(subject, "subject");
        if (issuer.length() > MAX_COMPONENT_LENGTH) {
            throw new IllegalArgumentException("issuer is too long");
        }
        if (subject.length() > MAX_COMPONENT_LENGTH) {
            throw new IllegalArgumentException("subject is too long");
        }
        URI parsed;
        try {
            parsed = URI.create(issuer);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("issuer must be a valid absolute URI", exception);
        }
        if (!parsed.isAbsolute()) {
            throw new IllegalArgumentException("issuer must be a valid absolute URI");
        }
    }

    private static String requireExactText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must not contain surrounding whitespace");
        }
        return value;
    }
}
