package org.mariadb.jdbc;

import org.junit.Test;
import org.mariadb.jdbc.plugin.authentication.addon.gssapi.GssUtility;
import org.mariadb.jdbc.plugin.authentication.standard.ParsecPasswordPluginFactory;
import org.mariadb.jdbc.pool.Pool;
import static org.junit.Assert.*;

public class AndroidProfileTest {
    @Test public void unsupportedAuthenticationFailsExplicitly() {
        assertThrows(UnsupportedOperationException.class, GssUtility::getAuthenticationMethod);
        assertThrows(UnsupportedOperationException.class,
                () -> new ParsecPasswordPluginFactory().initialize("synthetic", new byte[0], null, null));
    }
    @Test public void jmxIsRejectedBeforeStartingPool() throws Exception {
        Configuration config = Configuration.parse("jdbc:mariadb://localhost:1/synthetic?registerJmxPool=true");
        assertThrows(IllegalArgumentException.class, () -> new Pool(config, 1, null));
    }
}
