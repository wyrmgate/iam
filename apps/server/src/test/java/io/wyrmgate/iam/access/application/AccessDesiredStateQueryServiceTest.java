package io.wyrmgate.iam.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessDesiredStateQueryServiceTest {

    @Test
    void returnsUnavailableWhenProjectionStoreCannotBeRead() {
        var repository = mock(DesiredStateProjectionRepository.class);
        var tenant = new TenantContext(UUID.randomUUID());
        var subjectId = UUID.randomUUID();
        when(repository.principalFreshness(tenant, subjectId))
                .thenThrow(new IllegalStateException("projection store unavailable"));

        var result = new AccessDesiredStateQueryService(repository).current(
                tenant, DesiredAccessStateQuery.SubjectKind.DESIRED_PRINCIPAL, subjectId);

        assertThat(result).isEqualTo(DesiredAccessStateQuery.Freshness.unavailable());
    }
}
