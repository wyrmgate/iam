package io.wyrmgate.iam.idp.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import io.wyrmgate.iam.idp.session.IdpSessionTokenCodec;
import io.wyrmgate.iam.platform.persistence.SharedPostgresIntegrationDatabase;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IdpInteractionPersistenceIntegrationTest {
    @Test void realDatabaseConsumesBoundInteractionOnlyOnceAcrossReplicas() throws Exception {
        var jdbc = SharedPostgresIntegrationDatabase.jdbc();
        var first = new JdbcIdpLoginInteractionRepository(jdbc);
        var second = new JdbcIdpLoginInteractionRepository(jdbc);
        var codec = new IdpSessionTokenCodec();
        var form = codec.issue();
        var browser = codec.issue();
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var request = new IdpProtocolService.AuthorizationRequest("code", "client", "https://example.test/callback",
                "openid", "state-1234567890abcdef", "A".repeat(43), "S256", null);
        var row = new IdpLoginInteractionRepository.Interaction(form.hash(), browser.hash(),
                new TenantContext(UUID.randomUUID()), UUID.randomUUID(), 1, UUID.randomUUID(), request, now.plusSeconds(300));
        first.insert(row);
        assertThat(second.consume(form.hash(), codec.issue().hash(), now)).isEmpty();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> first.consume(form.hash(), browser.hash(), now));
            var b = pool.submit(() -> second.consume(form.hash(), browser.hash(), now));
            var left = a.get(10, TimeUnit.SECONDS);
            var right = b.get(10, TimeUnit.SECONDS);
            assertThat(left.isPresent() ^ right.isPresent()).isTrue();
            assertThat(left.orElseGet(right::orElseThrow)).isEqualTo(row);
        }
        assertThat(first.consume(form.hash(), browser.hash(), now)).isEmpty();
    }
}
