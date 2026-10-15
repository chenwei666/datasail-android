package com.microsoft.sqlserver.jdbc;

import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.*;

public class AndroidAuthenticationTest {
    @Test public void unsupportedAuthenticationRejectsBeforeOpeningSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(100);
            String[][] cases = {{"integratedSecurity", "true"}, {"authentication", "ActiveDirectoryPassword"},
                    {"accessToken", "synthetic-token"}, {"accessTokenCallbackClass", "synthetic.Callback"}};
            for (String[] option : cases) {
                Properties props = new Properties();
                props.setProperty(option[0], option[1]);
                SQLException error = assertThrows(SQLException.class, () -> new SQLServerDriver().connect(
                        "jdbc:sqlserver://localhost:" + server.getLocalPort() + ";loginTimeout=1", props));
                assertEquals(option[0], "0A000", error.getSQLState());
                assertThrows(SocketTimeoutException.class, server::accept);
            }
        }
    }
}
