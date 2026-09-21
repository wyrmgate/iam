package io.wyrmgate.iam.integration.provider;

import java.util.Objects;

public final class EnvironmentConnectorSecretProvider implements ConnectorSecretProvider {

    private static final String PREFIX = "env:";

    @Override
    public char[] resolve(String secretReference) {
        String reference = Objects.requireNonNull(secretReference, "secretReference").trim();
        if (!reference.startsWith(PREFIX) || reference.length() == PREFIX.length()) {
            throw new IllegalArgumentException("connector secret reference must use env:<VARIABLE_NAME>");
        }
        String variable = reference.substring(PREFIX.length());
        if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("connector secret environment variable name is invalid");
        }
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("connector secret reference could not be resolved");
        }
        return value.toCharArray();
    }
}
