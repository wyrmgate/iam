package io.wyrmgate.experiment;

import io.vyrnforge.platform.kernel.context.*;
import io.vyrnforge.platform.kernel.identity.*;
import io.vyrnforge.platform.kernel.pipeline.*;
import io.vyrnforge.platform.kernel.errors.KernelAccessException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KernelCoreTest {
    static final UUID T1=UUID.fromString("019a7f0e-2234-7abc-8def-1234567890ab");
    static final UUID T2=UUID.fromString("019a7f0e-2234-7abc-8def-1234567890ac");

    // Synthetic adapter inputs are verified provenance, never raw tokens.
    static ValidatedIdentityCredential verified(String issuer, String sub, String actor) {
        return new ValidatedIdentityCredential(
            new ActorReference(ActorType.USER,actor,new IdentityReference(issuer,sub),null),null,null);
    }
    @Test void exactProvenanceAndScope() {
        var a=verified("https://iam.example.invalid","same","actor-one");
        var b=verified("https://other.example.invalid","same","actor-two");
        var adapter=new ValidatedIdentityAdapter();
        assertNotEquals(adapter.authenticate(a,TenantContext.tenant(T1)).actor().actorId(),
                        adapter.authenticate(b,TenantContext.tenant(T1)).actor().actorId());
        assertEquals(T1,adapter.authenticate(a,TenantContext.tenant(T1)).tenant().tenantId());
        assertFalse(TenantContext.none().isTenantScoped());
        assertThrows(IllegalArgumentException.class,()->TenantContext.tenant(UUID.randomUUID()));
    }
    @Test void tenantlessNeverImpliesDeploymentAuthorityAndRechecksGrant() {
        var a=verified("https://iam.example.invalid","same","actor-one");
        var allowed=new AtomicBoolean(true);
        var pipeline=new RequestContextPipeline<ValidatedIdentityCredential>(
            new ValidatedIdentityAdapter(), (actor,tenant)->tenant.equals(T1),
            (tenant,module)->null,
            (actor,action,resource)->allowed.get() && action.equals("health.read"));
        var health=new RequestContextInput<>(a,TenantContext.none(),null,EndpointPolicy.tenantless("health.read"),null);
        assertNotNull(pipeline.resolve(health));
        allowed.set(false);
        assertThrows(KernelAccessException.class,()->pipeline.resolve(health));
        assertThrows(KernelAccessException.class,()->pipeline.resolve(
            new RequestContextInput<>(a,TenantContext.none(),null,EndpointPolicy.tenantless("deployment.admin"),null)));
        assertThrows(KernelAccessException.class,()->pipeline.resolve(
            new RequestContextInput<>(a,TenantContext.tenant(T2),null,EndpointPolicy.tenantModule("storage","file.read"),null)));
        assertThrows(KernelAccessException.class,()->pipeline.resolve(
            new RequestContextInput<>(a,TenantContext.none(),null,EndpointPolicy.tenantModule("storage","file.read"),null)));
    }
}
