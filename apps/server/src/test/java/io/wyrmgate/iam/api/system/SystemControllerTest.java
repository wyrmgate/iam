package io.wyrmgate.iam.api.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SystemControllerTest {

    @Test
    void exposesProductIdentity() {
        var info = new SystemController().info();

        assertThat(info)
                .containsEntry("name", "Wyrmgate IAM")
                .containsEntry("status", "development");
    }
}
