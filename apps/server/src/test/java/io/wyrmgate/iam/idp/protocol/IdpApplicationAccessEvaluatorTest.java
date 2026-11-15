package io.wyrmgate.iam.idp.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.wyrmgate.iam.access.application.EffectiveAccessQuery;
import io.wyrmgate.iam.catalog.application.CatalogQueryModels.*;
import io.wyrmgate.iam.catalog.application.CatalogQueryService;
import io.wyrmgate.iam.catalog.application.CatalogTargetReferenceQuery.Validation;
import io.wyrmgate.iam.catalog.domain.*;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdpApplicationAccessEvaluatorTest {
    private final TenantContext tenant = new TenantContext(UUID.randomUUID());
    private final UUID identity = UUID.randomUUID();
    private final UUID app = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-15T08:00:00Z");
    private final CatalogQueryService catalog = mock(CatalogQueryService.class);
    private final EffectiveAccessQuery access = mock(EffectiveAccessQuery.class);
    private final IdpApplicationAccessEvaluator evaluator = new IdpApplicationAccessEvaluator(catalog, access);

    @Test void retiredTargetCannotProveApplicationAccess() {
        var entitlement = new Entitlement(UUID.randomUUID(), app, target, "entitlement",
                CatalogLifecycleState.ACTIVE, 1, now, now);
        when(catalog.listEntitlements(tenant, app, null, 200)).thenReturn(new EntitlementPage(List.of(entitlement), null));
        when(catalog.validateActiveTarget(tenant, target)).thenReturn(Validation.RETIRED);
        assertThat(evaluator.hasCurrentAccess(tenant, identity, app, now)).isFalse();
        verifyNoInteractions(access);
    }

    @Test void partialScanExhaustionIsUnavailableNotAuthoritativeAbsence() {
        when(catalog.listEntitlements(eq(tenant), eq(app), nullable(PagePosition.class), eq(200)))
                .thenAnswer(invocation -> new EntitlementPage(List.of(), new PagePosition(now, UUID.randomUUID())));
        assertThatThrownBy(() -> evaluator.hasCurrentAccess(tenant, identity, app, now))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("page budget");
        verify(catalog, times(10)).listEntitlements(eq(tenant), eq(app), nullable(PagePosition.class), eq(200));
        verifyNoInteractions(access);
    }

    @Test void repeatedCursorFailsClosed() {
        var cursor = new PagePosition(now, UUID.randomUUID());
        when(catalog.listEntitlements(eq(tenant), eq(app), nullable(PagePosition.class), eq(200)))
                .thenReturn(new EntitlementPage(List.of(), cursor));
        assertThatThrownBy(() -> evaluator.hasCurrentAccess(tenant, identity, app, now))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cursor");
    }
}
