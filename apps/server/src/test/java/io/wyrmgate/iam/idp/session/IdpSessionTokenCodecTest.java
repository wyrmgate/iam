package io.wyrmgate.iam.idp.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdpSessionTokenCodecTest {

    @Test
    void issuedTokensAreOpaqueUniqueAndOnlyDigestIsStorageMaterial() {
        IdpSessionTokenCodec codec = new IdpSessionTokenCodec();

        IdpSessionTokenCodec.IssuedToken first = codec.issue();
        IdpSessionTokenCodec.IssuedToken second = codec.issue();

        assertThat(first.value()).hasSize(43).isNotEqualTo(second.value());
        assertThat(first.hash()).hasSize(64).isEqualTo(codec.hash(first.value()));
        assertThat(first.hash()).doesNotContain(first.value());
        assertThat(first.toString()).doesNotContain(first.value()).contains("REDACTED");
    }
}
