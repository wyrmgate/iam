package io.wyrmgate.iam.platform.tenant;

import io.wyrmgate.iam.platform.persistence.TransactionExecutor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Platform-owned semantic command for establishing the first tenant isolation root.
 * This is intentionally not a general-purpose tenant administration service.
 */
public final class TenantProvisioningService {

    private final TenantProvisioningRepository repository;
    private final TransactionExecutor transactions;

    public TenantProvisioningService(
            TenantProvisioningRepository repository,
            TransactionExecutor transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public TenantContext ensureFirstTenant(
            UUID tenantId,
            String displayName,
            Instant now) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(now, "now");
        String normalizedName = requireText(displayName, "displayName", 512);

        return transactions.required(() -> {
            var existing = repository.find(tenantId).orElse(null);
            if (existing != null) {
                if (!existing.displayName().equals(normalizedName)) {
                    throw new IllegalStateException(
                            "preallocated Tenant already exists with a different display name");
                }
                return new TenantContext(tenantId);
            }
            if (repository.countTenants() != 0L) {
                throw new IllegalStateException(
                        "fresh-install bootstrap requires an empty tenant registry");
            }
            repository.insert(tenantId, normalizedName, now);
            return new TenantContext(tenantId);
        });
    }

    public boolean matches(UUID tenantId, String displayName) {
        if (tenantId == null || displayName == null) return false;
        String normalizedName = displayName.trim();
        return repository.find(tenantId)
                .filter(record -> record.displayName().equals(normalizedName))
                .isPresent();
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds maximum length");
        }
        return normalized;
    }
}
