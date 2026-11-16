package io.wyrmgate.iam.api.security;

import io.wyrmgate.iam.administration.application.AuthenticatedAdministrativeActor;
import io.wyrmgate.iam.administration.domain.ExternalAuthenticationSubject;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KernelControlPlaneIdentityAdapterTest {
    @Test void exactProvenanceNeverCoalescesIssuersOrNormalizesSubject() {
        var a=KernelControlPlaneIdentityAdapter.externalSubject("https://one.example.test","same");
        var b=KernelControlPlaneIdentityAdapter.externalSubject("https://two.example.test","same");
        assertEquals(new ExternalAuthenticationSubject("https://one.example.test","same"),a);
        assertNotEquals(a,b);
        assertThrows(IllegalArgumentException.class,()->KernelControlPlaneIdentityAdapter.externalSubject("not-absolute","same"));
        assertThrows(IllegalArgumentException.class,()->KernelControlPlaneIdentityAdapter.externalSubject("https://one.example.test"," same"));
        assertThrows(IllegalArgumentException.class,()->KernelControlPlaneIdentityAdapter.externalSubject(null,"same"));
        assertThrows(IllegalArgumentException.class,()->KernelControlPlaneIdentityAdapter.externalSubject("https://one.example.test","x".repeat(513)));
    }
    @Test void explicitTenantProjectionPreservesIamIdsAndNeverUsesNone() {
        var ids=new UuidV7Generator();
        var first=ids.nextId();
        var second=ids.nextId();
        assertTrue(first.compareTo(second)<0);
        var actor=new AuthenticatedAdministrativeActor(new TenantContext(first),second);
        var scope=KernelControlPlaneIdentityAdapter.tenantScope(actor);
        assertTrue(scope.isTenantScoped());
        assertEquals(first,scope.tenantId());
        var legacy=new AuthenticatedAdministrativeActor(new TenantContext(UUID.randomUUID()),second);
        assertThrows(IllegalArgumentException.class,()->KernelControlPlaneIdentityAdapter.tenantScope(legacy));
        assertNotNull(legacy.tenant().tenantId()); // No change to existing IAM-only semantics.
    }
}
