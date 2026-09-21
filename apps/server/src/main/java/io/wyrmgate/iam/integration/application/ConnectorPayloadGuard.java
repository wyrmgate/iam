package io.wyrmgate.iam.integration.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ConnectorPayloadGuard {

    private static final Set<String> FORBIDDEN = Set.of(
            "password", "privatekey", "refreshtoken", "secretvalue", "clientsecret");

    private ConnectorPayloadGuard() {
    }

    public static void requireSecretFree(Object value) {
        inspect(value);
    }

    private static void inspect(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey())
                        .replace("_", "")
                        .replace("-", "")
                        .toLowerCase(Locale.ROOT);
                if (FORBIDDEN.contains(key)) {
                    throw new WorkerProtocolException(
                            "secret_material_forbidden",
                            "ordinary connector-worker payload contains a forbidden secret-shaped field");
                }
                inspect(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) inspect(item);
        }
    }
}
