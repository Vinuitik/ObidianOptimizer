package com.obsidian.obsidian.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** JDBC-URL parsing that feeds pg_dump/pg_restore host/port/db args. */
class DbBackupServiceTest {

    @Test
    void parsesStandardJdbcUrl() {
        DbBackupService.Db db = DbBackupService.parseJdbc("jdbc:postgresql://postgres:5432/obsidian");
        assertThat(db.host()).isEqualTo("postgres");
        assertThat(db.port()).isEqualTo("5432");
        assertThat(db.name()).isEqualTo("obsidian");
    }

    @Test
    void parsesUrlWithParamsAndDefaultsPort() {
        DbBackupService.Db a = DbBackupService.parseJdbc("jdbc:postgresql://db.host/mydb?sslmode=require&x=1");
        assertThat(a.host()).isEqualTo("db.host");
        assertThat(a.port()).isEqualTo("5432");   // no explicit port → default
        assertThat(a.name()).isEqualTo("mydb");
    }
}
