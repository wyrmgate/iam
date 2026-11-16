package io.wyrmgate.experiment;

import io.vyrnforge.platform.kernel.context.TenantContext;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import io.wyrmgate.iam.platform.id.UuidV7Generator;
import java.time.*;
import java.security.SecureRandom;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Test-only adapter compiles actual IAM source types copied from the checked-out baseline.
class ActualIamAdapterTest {
    static TenantContext tenantAdapter(io.wyrmgate.iam.platform.tenant.TenantContext iam) {
        return TenantContext.tenant(iam.tenantId());
    }
    @Test void stableIamTenantAndGeneratedIdsCrossKernelBoundary() {
        var ids=new UuidV7Generator();
        UUID previous=ids.nextId();
        var iam=new io.wyrmgate.iam.platform.tenant.TenantContext(previous);
        assertEquals(previous,tenantAdapter(iam).tenantId());
        for(int i=0;i<1000;i++){
            UUID id=ids.nextId();
            assertTrue(UuidV7.isCanonical(id.toString()));
            assertTrue(id.compareTo(previous)>0,"IAM generator promises monotonic local ordering");
            previous=id;
        }
        assertThrows(IllegalArgumentException.class,()->tenantAdapter(
            new io.wyrmgate.iam.platform.tenant.TenantContext(UUID.randomUUID())));
    }
    @Test void kernelCanonicalIdDoesNotPromiseIamMonotonicOrdering() {
        var clock=Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"),ZoneOffset.UTC);
        var rng=new SecureRandom(){
            private final long[] values={4095L,0L,0L,0L}; private int index;
            @Override public long nextLong(){return values[index++];}
        };
        UUID first=UuidV7.generate(clock,rng),second=UuidV7.generate(clock,rng);
        assertTrue(UuidV7.isCanonical(first.toString()));
        assertTrue(UuidV7.isCanonical(second.toString()));
        assertTrue(first.compareTo(second)>0,"Kernel v0.3.2 uses random intra-ms bits, not monotonic counter");
    }
}
