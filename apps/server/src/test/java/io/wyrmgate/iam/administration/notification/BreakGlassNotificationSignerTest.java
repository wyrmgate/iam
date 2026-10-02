package io.wyrmgate.iam.administration.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BreakGlassNotificationSignerTest {

    @Test
    void signatureIsStableAndVersioned() {
        var signer = new BreakGlassNotificationSigner(
                "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
        byte[] body = "{\"type\":\"test\"}".getBytes(StandardCharsets.UTF_8);

        String first = signer.sign(1_700_000_000L, body);
        String second = signer.sign(1_700_000_000L, body);

        assertThat(first).startsWith("v1=").hasSize(67);
        assertThat(first).isEqualTo(second);
        assertThat(signer.sign(1_700_000_001L, body)).isNotEqualTo(first);
    }
}
