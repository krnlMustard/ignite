package org.apache.ignite.cache.store.jdbc;

import javax.cache.configuration.Factory;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcConnectionPool;

/**
 * Datasource to use for store tests.
 */
public class H2DataSourceFactory implements Factory<DataSource> {
    /** DB connection URL. */
    private static final String DFLT_CONN_URL = "jdbc:h2:mem:TestDatabase;DB_CLOSE_DELAY=-1";

    /** {@inheritDoc} */
    @Override public DataSource create() {
        return JdbcConnectionPool.create(DFLT_CONN_URL, "sa", "");
    }
}
