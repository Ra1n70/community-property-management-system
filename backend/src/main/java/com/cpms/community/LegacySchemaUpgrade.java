package com.cpms.community;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.util.Map;

/**
 * ddl-auto=update adds new columns but never rewrites the enum CHECK constraints Hibernate created earlier,
 * so existing PostgreSQL databases would reject newly added enum values. Drop those stale constraints.
 */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacySchemaUpgrade implements ApplicationRunner {
    /** table -> stale CHECK constraint on an enum column whose values grew. */
    static final Map<String,String> ENUM_CHECKS=Map.of("maintenance_tickets","maintenance_tickets_status_check");
    private final DataSource dataSource;
    public LegacySchemaUpgrade(DataSource dataSource){this.dataSource=dataSource;}
    @Override public void run(ApplicationArguments args) throws Exception {
        try(var connection=dataSource.getConnection()) {
            if(!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))return;
        }
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        ENUM_CHECKS.forEach((table,constraint)->jdbc.execute("ALTER TABLE IF EXISTS "+table+" DROP CONSTRAINT IF EXISTS "+constraint));
    }
}
