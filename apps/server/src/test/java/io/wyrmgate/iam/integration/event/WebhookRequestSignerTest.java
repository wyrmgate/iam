package io.wyrmgate.iam.integration.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookRequestSignerTest {

    @Test
    void signsTimestampDotRawBodyWithHmacSha256() {
        WebhookRequestSigner signer =
                new WebhookRequestSigner("ssssssssssssssssssssssssssssssss".getBytes(StandardCharsets.UTF_8));

        String signature = signer.sign(
                1700000000L,
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        assertThat(signature)
                .isEqualTo("v1=309f315ed918800b6520ea268513d815910b912ddf2ca1d08eb7fdfaaf0e3676");
    }
}
