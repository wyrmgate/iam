package io.wyrmgate.iam.idp.federation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of explicitly wired upstream federation adapters.
 *
 * <p>No adapter is enabled by framework discovery or by protocol-library capability alone.
 * Deployments must register an explicit provider implementation and a provider-specific endpoint
 * may then depend on that typed adapter. The registry intentionally exposes descriptors rather
 * than an untyped generic invocation path.</p>
 */
public final class FederationAdapterRegistry {

    private final Map<String, FederatedAuthenticationAdapter<?>> adapters;

    public FederationAdapterRegistry(Collection<? extends FederatedAuthenticationAdapter<?>> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        Map<String, FederatedAuthenticationAdapter<?>> indexed = new LinkedHashMap<>();
        for (FederatedAuthenticationAdapter<?> adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter");
            String key = requireKey(adapter.providerKey());
            Objects.requireNonNull(adapter.protocol(), "protocol");
            Objects.requireNonNull(adapter.requestType(), "requestType");
            if (indexed.putIfAbsent(key, adapter) != null) {
                throw new IllegalArgumentException("duplicate federation provider key " + key);
            }
        }
        this.adapters = Map.copyOf(indexed);
    }

    public Optional<Descriptor> descriptor(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) return Optional.empty();
        FederatedAuthenticationAdapter<?> adapter = adapters.get(providerKey);
        if (adapter == null) return Optional.empty();
        return Optional.of(new Descriptor(
                adapter.providerKey(), adapter.protocol(), adapter.requestType().getName()));
    }

    public List<Descriptor> descriptors() {
        return adapters.values().stream()
                .map(adapter -> new Descriptor(
                        adapter.providerKey(), adapter.protocol(), adapter.requestType().getName()))
                .sorted(java.util.Comparator.comparing(Descriptor::providerKey))
                .toList();
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("federation provider key must not be blank");
        }
        String normalized = value.trim();
        if (!normalized.equals(value)) {
            throw new IllegalArgumentException("federation provider key must not contain surrounding whitespace");
        }
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("federation provider key is too long");
        }
        return normalized;
    }

    public record Descriptor(
            String providerKey,
            FederationProtocol protocol,
            String requestType) {
    }
}
