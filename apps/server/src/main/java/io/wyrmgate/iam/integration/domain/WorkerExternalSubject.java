package io.wyrmgate.iam.integration.domain;

import java.net.URI;

public record WorkerExternalSubject(String issuer, String subject) {
    private static final int MAX = 512;

    public WorkerExternalSubject {
        issuer = exact(issuer, "issuer");
        subject = exact(subject, "subject");
        if (issuer.length() > MAX || subject.length() > MAX) {
            throw new IllegalArgumentException("worker external subject component is too long");
        }
        URI parsed;
        try {
            parsed = URI.create(issuer);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("issuer must be a valid absolute URI", invalid);
        }
        if (!parsed.isAbsolute()) {
            throw new IllegalArgumentException("issuer must be a valid absolute URI");
        }
    }

    private static String exact(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        if (!value.equals(value.strip())) throw new IllegalArgumentException(name + " must not contain surrounding whitespace");
        return value;
    }
}
