package io.wyrmgate.iam.platform.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    @Test
    void generatesUniqueRfcUuidV7ValuesInMonotonicImplementationOrder() {
        UuidV7Generator generator = new UuidV7Generator();
        Set<UUID> ids = new HashSet<>();
        UUID previous = null;

        for (int index = 0; index < 10_000; index++) {
            UUID current = generator.nextId();
            assertThat(current.version()).isEqualTo(7);
            assertThat(current.variant()).isEqualTo(2);
            assertThat(ids.add(current)).isTrue();
            if (previous != null) {
                assertThat(current.toString()).isGreaterThan(previous.toString());
            }
            previous = current;
        }
    }
}
