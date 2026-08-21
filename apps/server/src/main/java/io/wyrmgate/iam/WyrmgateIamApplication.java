package io.wyrmgate.iam;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class WyrmgateIamApplication {

    private static final String MIGRATE_ONLY_ARGUMENT = "--wyrmgate.migrate-only=true";

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(WyrmgateIamApplication.class, args);
        if (Arrays.asList(args).contains(MIGRATE_ONLY_ARGUMENT)) {
            int exitCode = SpringApplication.exit(context);
            System.exit(exitCode);
        }
    }
}
