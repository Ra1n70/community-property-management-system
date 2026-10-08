package com.cpms.community.amenity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * Fees used to be stored as double precision. ddl-auto=update never changes an existing column's type,
 * so existing PostgreSQL databases are converted to numeric(10,2) here, once; already converted or new
 * databases are left alone.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AmenitySchemaUpgrade implements ApplicationRunner {
    static final List<String> FEE_TABLES = List.of("amenities", "reservations");

    private final DataSource dataSource;

    public AmenitySchemaUpgrade(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) return;
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String table : FEE_TABLES) {
            List<String> types = jdbc.queryForList(
                    "select data_type from information_schema.columns where table_schema = current_schema() and table_name = ? and column_name = 'fee'",
                    String.class, table);
            if (types.contains("double precision") || types.contains("real")) {
                jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN fee TYPE numeric(10,2) USING round(fee::numeric, 2)");
            }
        }
    }
}
