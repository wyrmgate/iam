package io.wyrmgate.iam.integration.provider;

public interface ConnectorSecretProvider {

    char[] resolve(String secretReference);

    static void destroy(char[] secret) {
        if (secret != null) {
            java.util.Arrays.fill(secret, '\0');
        }
    }
}
