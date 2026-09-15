package io.wyrmgate.iam.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class RequiredAudienceValidatorTest {

    @Test
    void requiresConfiguredAudienceWithoutTreatingScopesAsAuthority() {
        RequiredAudienceValidator validator = new RequiredAudienceValidator("wyrmgate-api");
        Jwt matching = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("aud", List.of("other", "wyrmgate-api"))
                .claim("scope", "identity.write administration.superuser")
                .build();
        Jwt missing = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("aud", List.of("other"))
                .build();

        assertThat(validator.validate(matching).hasErrors()).isFalse();
        assertThat(validator.validate(missing).hasErrors()).isTrue();
    }
}
