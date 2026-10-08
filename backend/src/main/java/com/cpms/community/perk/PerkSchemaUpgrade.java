package com.cpms.community.perk;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * The first Local Perks version stored perks in the same {@code local_perks} table with a different layout
 * (redeem code, optional end date, required {@code created_by}). ddl-auto=update cannot add the new required
 * columns to a table with rows and never drops the old required column, so creating perks would fail.
 * Existing PostgreSQL databases with the old layout are cleared and converted here, once; managers re-enter
 * their perks. New or already converted databases are left alone.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PerkSchemaUpgrade implements ApplicationRunner {
    private final DataSource dataSource;

    public PerkSchemaUpgrade(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) return;
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer oldLayout = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = current_schema() and table_name = 'local_perks' and column_name = 'redeem_code'",
                Integer.class);
        if (oldLayout == null || oldLayout == 0) return;
        jdbc.execute("DELETE FROM local_perks");
        jdbc.execute("ALTER TABLE local_perks"
                + " DROP COLUMN IF EXISTS redeem_code, DROP COLUMN IF EXISTS valid_until, DROP COLUMN IF EXISTS created_by,"
                + " ADD COLUMN IF NOT EXISTS contact varchar(40), ADD COLUMN IF NOT EXISTS start_at timestamp(6) with time zone,"
                + " ADD COLUMN IF NOT EXISTS end_at timestamp(6) with time zone NOT NULL, ADD COLUMN IF NOT EXISTS published boolean NOT NULL,"
                + " ADD COLUMN IF NOT EXISTS created_by_id bigint");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_local_perks_category ON local_perks (category)");
    }
}
