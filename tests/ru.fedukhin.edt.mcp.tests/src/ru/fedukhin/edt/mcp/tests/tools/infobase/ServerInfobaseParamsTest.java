package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ServerInfobaseParams;

public class ServerInfobaseParamsTest {

    private static ServerInfobaseParams params(String dbms, String dbUser, String dbPassword,
                                               String clusterUser, String clusterPassword) {
        return new ServerInfobaseParams("localhost", "Demo", dbms, "pg-host", "demo_db", dbUser,
            dbPassword, true, true, clusterUser, clusterPassword);
    }

    @Test
    public void argument_hasAllKeysInPlatformOrder() {
        assertEquals("Srvr=localhost;Ref=Demo;DBMS=PostgreSQL;DBSrvr=pg-host;DB=demo_db;DBUID=postgres;"
                + "DBPwd=S3cr3t;CrSQLDB=Y;SchJobDn=Y",
            params("PostgreSQL", "postgres", "S3cr3t", null, null).toCreateInfobaseArgument());
    }

    @Test
    public void noPassword_omitsDbPwd_andFlagsCanBeOff() {
        ServerInfobaseParams p = new ServerInfobaseParams("localhost", "Demo", "MSSQLServer", "sql",
            "demo", "sa", null, false, false, null, null);
        assertEquals("Srvr=localhost;Ref=Demo;DBMS=MSSQLServer;DBSrvr=sql;DB=demo;DBUID=sa;CrSQLDB=N;SchJobDn=N",
            p.toCreateInfobaseArgument());
    }

    @Test
    public void clusterAdmin_addsSUsrAndSPwd() {
        String arg = params("PostgreSQL", "postgres", null, "ClusterAdm", "Adm1nPass").toCreateInfobaseArgument();
        assertTrue(arg, arg.endsWith(";SUsr=ClusterAdm;SPwd=Adm1nPass"));
    }

    @Test
    public void describe_masksPasswords() {
        String d = params("PostgreSQL", "postgres", "S3cr3t", "ClusterAdm", "Adm1nPass").describe();
        assertFalse(d, d.contains("S3cr3t"));
        assertFalse(d, d.contains("Adm1nPass"));
        assertTrue(d, d.contains("DBPwd=***"));
        assertTrue(d, d.contains("SPwd=***"));
    }

    /**
     * record генерирует свой toString() автоматически — он печатал бы dbPassword/clusterPassword
     * в открытом виде, если бы не был переопределён на describe().
     */
    @Test
    public void toString_masksPasswords() {
        String s = params("PostgreSQL", "postgres", "S3cr3t", "ClusterAdm", "Adm1nPass").toString();
        assertFalse(s, s.contains("S3cr3t"));
        assertFalse(s, s.contains("Adm1nPass"));
    }

    @Test
    public void validate_rejectsSemicolonInPassword() {
        expectInvalid(params("PostgreSQL", "postgres", "a;b", null, null), "dbPassword");
    }

    @Test
    public void validate_rejectsUnknownDbms() {
        expectInvalid(params("SQLite", "postgres", null, null, null), "dbms");
    }

    @Test
    public void validate_rejectsNullDbms() {
        expectInvalid(params(null, "postgres", null, null, null), "dbms");
    }

    @Test
    public void validate_requiresDbUser() {
        expectInvalid(params("PostgreSQL", null, null, null, null), "dbUser");
    }

    private static void expectInvalid(ServerInfobaseParams p, String field) {
        try {
            p.validate();
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(field));
        }
    }
}
