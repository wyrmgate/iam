package io.wyrmgate.iam.access.persistence;

import io.wyrmgate.iam.access.application.AccessDesiredStateQueryService;
import io.wyrmgate.iam.access.application.DesiredAccessStateQuery;
import io.wyrmgate.iam.access.application.DesiredStateProjectionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AccessPersistenceConfiguration {

    @Bean
    DesiredStateProjectionRepository desiredStateProjectionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcDesiredStateProjectionRepository(jdbcTemplate);
    }

    @Bean
    DesiredAccessStateQuery desiredAccessStateQuery(DesiredStateProjectionRepository repository) {
        return new AccessDesiredStateQueryService(repository);
    }
}
