package ru.fedukhin.edt.mcp.tools.infobase.internal;

import java.util.ArrayList;
import java.util.List;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Параметры серверной базы для {@code 1cv8 CREATEINFOBASE}.
 *
 * <p>Строка подключения уходит в 1cv8 одним аргументом <b>без кавычек</b>: {@code ProcessBuilder}
 * на Windows экранирует встроенные кавычки как {@code \"}, и 1cv8 такую строку не понимает
 * (та же грабля, что у {@code File=} в {@link RuntimeCli}). Поэтому значения не должны
 * содержать пробелов, {@code ;} и {@code "} — это проверяет {@link #validate()}.
 */
public record ServerInfobaseParams(String server, String ref, String dbms, String dbServer, String dbName,
                                   String dbUser, String dbPassword, boolean createDatabase,
                                   boolean lockScheduledJobs, String clusterUser, String clusterPassword) {

    public static final List<String> DBMS_TYPES = List.of("PostgreSQL", "MSSQLServer", "IBMDB2", "OracleDatabase");

    public void validate() throws ToolException {
        require("server", server);
        require("ref", ref);
        require("dbServer", dbServer);
        require("dbName", dbName);
        require("dbUser", dbUser);
        require("dbms", dbms);
        if (!DBMS_TYPES.contains(dbms)) {
            throw new ToolException("dbms must be one of " + DBMS_TYPES + ", got '" + dbms + "'");
        }
        checkChars("server", server);
        checkChars("ref", ref);
        checkChars("dbServer", dbServer);
        checkChars("dbName", dbName);
        checkChars("dbUser", dbUser);
        checkChars("dbPassword", dbPassword);
        checkChars("clusterUser", clusterUser);
        checkChars("clusterPassword", clusterPassword);
    }

    /**
     * Аргумент CREATEINFOBASE:
     * {@code Srvr=…;Ref=…;DBMS=…;DBSrvr=…;DB=…;DBUID=…[;DBPwd=…];CrSQLDB=Y|N;SchJobDn=Y|N[;SUsr=…[;SPwd=…]]}.
     */
    public String toCreateInfobaseArgument() {
        return build(true);
    }

    /** Та же строка без паролей — для ответов и журнала задания. */
    public String describe() {
        return build(false);
    }

    /**
     * Переопределён, иначе сгенерированный record-{@code toString()} печатает {@code dbPassword}/
     * {@code clusterPassword} в открытом виде (например, в логе исключения или отладочном выводе) —
     * рядом с {@link #describe()}, который те же пароли маскирует, это была бы утечка.
     */
    @Override
    public String toString() {
        return describe();
    }

    private String build(boolean withSecrets) {
        List<String> parts = new ArrayList<>();
        parts.add("Srvr=" + server);
        parts.add("Ref=" + ref);
        parts.add("DBMS=" + dbms);
        parts.add("DBSrvr=" + dbServer);
        parts.add("DB=" + dbName);
        parts.add("DBUID=" + dbUser);
        if (dbPassword != null && !dbPassword.isEmpty()) {
            parts.add("DBPwd=" + (withSecrets ? dbPassword : "***"));
        }
        parts.add("CrSQLDB=" + (createDatabase ? "Y" : "N"));
        parts.add("SchJobDn=" + (lockScheduledJobs ? "Y" : "N"));
        if (clusterUser != null && !clusterUser.isEmpty()) {
            parts.add("SUsr=" + clusterUser);
            if (clusterPassword != null && !clusterPassword.isEmpty()) {
                parts.add("SPwd=" + (withSecrets ? clusterPassword : "***"));
            }
        }
        return String.join(";", parts);
    }

    private static void require(String name, String value) throws ToolException {
        if (value == null || value.isBlank()) {
            throw new ToolException("'" + name + "' is required for a SERVER infobase");
        }
    }

    private static void checkChars(String name, String value) throws ToolException {
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == ';' || c == '"') {
                throw new ToolException("'" + name + "' must not contain spaces, ';' or '\"': 1cv8 receives "
                    + "the connection string as a single unquoted argument");
            }
        }
    }
}
