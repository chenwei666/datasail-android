package org.postgresql;

import java.util.Properties;
import org.junit.Test;
import org.postgresql.util.KerberosTicket;
import org.postgresql.util.internal.JvmHeapAccess;
import org.postgresql.jdbc.PgSQLXML;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.stax.StAXSource;
import java.sql.SQLFeatureNotSupportedException;
import static org.junit.Assert.*;

public class AndroidProfileTest {
    @Test public void heapLimitUsesAndroidRuntimeAndNoJaasCacheIsAdvertised() {
        assertEquals(Runtime.getRuntime().maxMemory(), JvmHeapAccess.maxHeapBytes());
        assertFalse(KerberosTicket.credentialCacheExists(new Properties()));
    }
    @Test public void xmlTextAndStreamRemainUsableWhileStaxExplicitlyRejects() throws Exception {
        PgSQLXML xml = new PgSQLXML(null);
        xml.setString("<value>中文 &amp; text</value>");
        assertEquals("<value>中文 &amp; text</value>", xml.getString());
        assertNotNull(xml.getSource(StreamSource.class));
        assertEquals("0A000", assertThrows(SQLFeatureNotSupportedException.class,
                () -> xml.getSource(StAXSource.class)).getSQLState());
        xml.free();
        assertThrows(java.sql.SQLException.class, xml::getString);
    }
}
