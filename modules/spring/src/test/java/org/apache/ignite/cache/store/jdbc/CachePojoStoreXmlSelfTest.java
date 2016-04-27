package org.apache.ignite.cache.store.jdbc;

import java.net.URL;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteComponentType;
import org.apache.ignite.internal.util.spring.IgniteSpringHelper;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.marshaller.Marshaller;

/**
 * Tests for {@code PojoCacheStore} created via XML.
 */
public class CachePojoStoreXmlSelfTest extends CacheJdbcPojoStoreAbstractSelfTest {
    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        String path = builtinKeys ?  "modules/spring/src/test/config/jdbc-pojo-store-builtin.xml" :
            "modules/spring/src/test/config/jdbc-pojo-store-obj.xml";

        URL url = U.resolveIgniteUrl(path);

        IgniteSpringHelper spring = IgniteComponentType.SPRING.create(false);

        IgniteConfiguration cfg = spring.loadConfigurations(url).get1().iterator().next();

        cfg.setGridName(gridName);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected Marshaller marshaller() {
        return null;
    }
}
