package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.version.Version;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli;
import ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli.DefaultExecutableResolver;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ServerInfobaseParams;

public class RuntimeCliTest {

    @Test
    public void createFileInfobase_happy_returnsZero() throws Exception {
        Path location = Paths.get("C:/tmp/IB");
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getVersion()).thenReturn(new Version("8.3.24"));
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntime("8.3.24")).thenReturn(runtime);
        when(reg.getRuntimes()).thenReturn(Collections.singletonList(runtime));

        RuntimeCli cli = new RuntimeCli(reg,
            v -> new File("C:/Program Files/1cv8/8.3.24/bin/1cv8.exe"),
            (cmd, dir) -> {
                Process p = mock(Process.class);
                try {
                    when(p.waitFor(60L, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
                } catch (InterruptedException ignored) {}
                when(p.exitValue()).thenReturn(0);
                when(p.getErrorStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
                return p;
            });
        int code = cli.createFileInfobase(location, "8.3.24", Duration.ofSeconds(60));
        assertEquals(0, code);
    }

    /**
     * Аргумент собирался как {@code File="<path>"} со встроенными кавычками. ProcessBuilder на
     * Windows экранирует их как {@code \"}, а 1cv8.exe такую форму не понимает и отвечает
     * «Неопределена информационная база» — этот же отказ проект уже задокументировал у себя в
     * TestRunnerLauncher.buildCommand. Кавычки вокруг пути с пробелами Windows расставляет сам.
     */
    @Test
    public void createFileInfobase_pathWithSpaces_isPassedWithoutEmbeddedQuotes() throws Exception {
        Path location = Paths.get("C:/Program Files/My IB");
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getVersion()).thenReturn(new Version("8.3.24"));
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntime("8.3.24")).thenReturn(runtime);
        when(reg.getRuntimes()).thenReturn(Collections.singletonList(runtime));

        java.util.List<java.util.List<String>> captured = new java.util.ArrayList<>();
        RuntimeCli cli = new RuntimeCli(reg,
            v -> new File("C:/Program Files/1cv8/8.3.24/bin/1cv8.exe"),
            (cmd, dir) -> {
                captured.add(cmd);
                Process p = mock(Process.class);
                try {
                    when(p.waitFor(60L, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
                } catch (InterruptedException ignored) {}
                when(p.exitValue()).thenReturn(0);
                when(p.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
                when(p.getErrorStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
                return p;
            });

        cli.createFileInfobase(location, "8.3.24", Duration.ofSeconds(60));

        java.util.List<String> cmd = captured.get(0);
        String fileArg = cmd.stream().filter(a -> a.startsWith("File=")).findFirst().orElse("<нет File=>");
        assertTrue("ожидался File=<путь> без кавычек, а получено: " + fileArg,
            fileArg.startsWith("File=") && !fileArg.contains("\""));
        assertTrue("путь обязан дойти целиком: " + fileArg, fileArg.contains("My IB"));
    }

    @Test
    public void createFileInfobase_unknownVersion_throwsToolException() {
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntime("9.9.9")).thenReturn(null);
        when(reg.getRuntimes()).thenReturn(Collections.emptyList());

        RuntimeCli cli = new RuntimeCli(reg,
            v -> { throw new IllegalStateException("not called"); },
            (c, d) -> { throw new IllegalStateException("not called"); });
        try {
            cli.createFileInfobase(Paths.get("C:/tmp/X"), "9.9.9", Duration.ofSeconds(10));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("9.9.9"));
        }
    }

    @Test
    public void createFileInfobase_processNonZero_throwsToolException() {
        Path location = Paths.get("C:/tmp/IB");
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getVersion()).thenReturn(new Version("8.3.24"));
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntime("8.3.24")).thenReturn(runtime);
        when(reg.getRuntimes()).thenReturn(Collections.singletonList(runtime));

        RuntimeCli cli = new RuntimeCli(reg,
            v -> new File("C:/Program Files/1cv8/8.3.24/bin/1cv8.exe"),
            (cmd, dir) -> {
                Process p = mock(Process.class);
                try {
                    when(p.waitFor(60L, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
                } catch (InterruptedException ignored) {}
                when(p.exitValue()).thenReturn(7);
                when(p.getErrorStream()).thenReturn(new java.io.ByteArrayInputStream("boom".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                return p;
            });
        try {
            cli.createFileInfobase(location, "8.3.24", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("7"));
        }
    }

    @Test
    public void createFileInfobase_timeout_destroysAndThrows() throws Exception {
        Path location = Paths.get("C:/tmp/IB");
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getVersion()).thenReturn(new Version("8.3.24"));
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntime("8.3.24")).thenReturn(runtime);
        when(reg.getRuntimes()).thenReturn(Collections.singletonList(runtime));

        Process p = mock(Process.class);
        when(p.waitFor(2L, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false);
        when(p.getErrorStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));

        RuntimeCli cli = new RuntimeCli(reg,
            v -> new File("C:/Program Files/1cv8/8.3.24/bin/1cv8.exe"),
            (cmd, dir) -> p);
        try {
            cli.createFileInfobase(location, "8.3.24", Duration.ofSeconds(2));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().toLowerCase().contains("timeout"));
        }
        org.mockito.Mockito.verify(p).destroyForcibly();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void defaultResolver_happy_returnsThickClientFile() throws Exception {
        IResolvableRuntimeInstallation resolvable = mock(IResolvableRuntimeInstallation.class);
        RuntimeInstallation install = mock(RuntimeInstallation.class);
        when(resolvable.resolve(any(), eq(AppArch.AUTO))).thenReturn(install);

        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        when(im.resolveByFullVersion(any(String.class), eq("8.3.24"), any(), eq(AppArch.AUTO)))
            .thenReturn(resolvable);

        ILaunchableRuntimeComponent component = mock(ILaunchableRuntimeComponent.class);
        when(component.getFile()).thenReturn(new java.io.File("C:/1cv8/8.3.24/bin/1cv8.exe"));
        IThickClientLauncher launcher = mock(IThickClientLauncher.class);
        ComponentExecutorInfo info = new ComponentExecutorInfo(install, component, launcher);
        IRuntimeComponentManager cm = mock(IRuntimeComponentManager.class);
        when(cm.resolveExecutor(eq(ILaunchableRuntimeComponent.class),
                eq(IThickClientLauncher.class), eq(install),
                eq(IRuntimeComponentTypes.THICK_CLIENT))).thenReturn(info);

        DefaultExecutableResolver resolver = new DefaultExecutableResolver(im, cm);
        java.io.File f = resolver.resolve("8.3.24");
        assertEquals(new java.io.File("C:/1cv8/8.3.24/bin/1cv8.exe").getAbsolutePath(), f.getAbsolutePath());
    }

    @Test
    public void defaultResolver_matchingRuntimeNotFound_throwsToolException() throws Exception {
        IResolvableRuntimeInstallationManager im = mock(IResolvableRuntimeInstallationManager.class);
        when(im.resolveByFullVersion(any(String.class), eq("9.9.9"), any(), eq(AppArch.AUTO)))
            .thenThrow(new MatchingRuntimeNotFound("nope"));

        DefaultExecutableResolver resolver = new DefaultExecutableResolver(im,
            mock(IRuntimeComponentManager.class));
        try {
            resolver.resolve("9.9.9");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("9.9.9"));
        }
    }

    private static IRuntimeRegistry registryWith(String version) {
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getVersion()).thenReturn(new Version(version));
        IRuntimeRegistry reg = mock(IRuntimeRegistry.class);
        when(reg.getRuntimes()).thenReturn(Collections.singletonList(runtime));
        return reg;
    }

    private static Process exitedWith(int code) {
        Process p = mock(Process.class);
        try {
            when(p.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        when(p.exitValue()).thenReturn(code);
        when(p.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(p.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return p;
    }

    @Test
    public void createServerInfobase_passesSingleUnquotedConnectionArgument_andOutLog() throws Exception {
        List<List<String>> captured = new ArrayList<>();
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"), serverFactory(captured, 0, EMPTY_DUMP, ""));
        ServerInfobaseParams params = new ServerInfobaseParams("localhost", "Demo", "PostgreSQL",
            "localhost", "demo_db", "postgres", "S3cr3t", true, true, null, null);

        assertEquals(0, cli.createServerInfobase(params, "8.3.27", Duration.ofSeconds(60)));

        List<String> cmd = captured.get(0);
        assertEquals("CREATEINFOBASE", cmd.get(1));
        assertEquals(params.toCreateInfobaseArgument(), cmd.get(2));
        int out = cmd.indexOf("/Out");
        assertTrue("нужен /Out <лог>: " + cmd, out > 0 && out + 1 < cmd.size());
        assertTrue(cmd.contains("/DisableStartupDialogs"));
    }

    /** Пустая свежая база (как у CREATEINFOBASE на новой БД): {@code <ConfigVersions/>} без {@code <Metadata}. */
    private static final String EMPTY_DUMP = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<ConfigDumpInfo xmlns=\"http://v8.1c.ru/8.3/xcf/dumpinfo\" format=\"Hierarchical\" version=\"2.21\">\n"
        + "\t<ConfigVersions/>\n</ConfigDumpInfo>\n";

    /** База с конфигурацией — так выглядит проверка, если 1С подключила новую базу к существующей БД. */
    private static final String CONFIGURED_DUMP = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<ConfigDumpInfo xmlns=\"http://v8.1c.ru/8.3/xcf/dumpinfo\" format=\"Hierarchical\" version=\"2.21\">\n"
        + "\t<ConfigVersions>\n"
        + "\t\t<Metadata name=\"CommonModule.ОбщийМодульSmoke\" id=\"6924308a-954b-45fb-a9ab-41389b88aefe\" "
        + "configVersion=\"fffe7e4a4c6d1240bbc195663b95414600000000\"/>\n"
        + "\t\t<Metadata name=\"Configuration.Конфигурация\" id=\"9ba7ab47-9fcb-44b2-a06c-a19d3e1deb0f\" "
        + "configVersion=\"d8a64f3b2d777d4fb7c168eae29b259800000000\"/>\n"
        + "\t</ConfigVersions>\n</ConfigDumpInfo>\n";

    /**
     * 1cv8-заглушка для серверной базы: CREATEINFOBASE завершается с кодом 0, проверочный
     * {@code DESIGNER /DumpConfigToFiles … -configDumpInfoOnly} — с {@code probeExit}, кладёт
     * {@code dump} (если не {@code null}) в каталог выгрузки и {@code probeOut} в лог {@code /Out}.
     */
    private static RuntimeCli.ProcessFactory serverFactory(List<List<String>> captured, int probeExit, String dump,
                                                           String probeOut) {
        return (cmd, dir) -> {
            captured.add(new ArrayList<>(cmd));
            if ("CREATEINFOBASE".equals(cmd.get(1))) return exitedWith(0);
            Path target = Paths.get(cmd.get(cmd.indexOf("/DumpConfigToFiles") + 1));
            Files.createDirectories(target);
            if (dump != null) Files.write(target.resolve("ConfigDumpInfo.xml"), dump.getBytes(StandardCharsets.UTF_8));
            Files.write(Paths.get(cmd.get(cmd.indexOf("/Out") + 1)), probeOut.getBytes(StandardCharsets.UTF_8));
            return exitedWith(probeExit);
        };
    }

    private static ServerInfobaseParams serverParams(String dbPassword, String clusterUser, String clusterPassword) {
        return new ServerInfobaseParams("localhost", "Demo", "PostgreSQL", "localhost", "demo_db", "postgres",
            dbPassword, true, true, clusterUser, clusterPassword);
    }

    /**
     * L4: CREATEINFOBASE с {@code CrSQLDB=Y} молча подключает новую базу к УЖЕ существующей БД с тем же
     * именем. Сразу после создания — проверка тем же 1cv8: {@code DESIGNER /S <сервер>\<база>
     * /DumpConfigToFiles <каталог> -configDumpInfoOnly}. Пустая выгрузка — база новая.
     */
    @Test
    public void createServerInfobase_probesNewInfobaseWithSameExecutable_emptyDumpAccepted() throws Exception {
        List<List<String>> captured = new ArrayList<>();
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"), serverFactory(captured, 0, EMPTY_DUMP, ""));

        assertEquals(0, cli.createServerInfobase(serverParams("S3cr3t", null, null), "8.3.27",
            Duration.ofSeconds(60)));

        assertEquals("CREATEINFOBASE, затем проверка: " + captured, 2, captured.size());
        List<String> probe = captured.get(1);
        assertEquals("проверка — тем же исполняемым файлом", captured.get(0).get(0), probe.get(0));
        assertEquals(List.of("DESIGNER", "/S", "localhost\\Demo", "/DumpConfigToFiles"), probe.subList(1, 5));
        assertEquals("-configDumpInfoOnly", probe.get(6));
        assertTrue(probe.contains("/DisableStartupDialogs"));
        assertFalse("проверке учётные данные СУБД не нужны: " + probe, String.valueOf(probe).contains("S3cr3t"));
        assertFalse("каталог выгрузки удаляется после проверки", Files.exists(Paths.get(probe.get(5))));
    }

    @Test
    public void createServerInfobase_existingDatabaseWithConfiguration_refused() {
        List<List<String>> captured = new ArrayList<>();
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"), serverFactory(captured, 0, CONFIGURED_DUMP, ""));
        try {
            cli.createServerInfobase(serverParams("S3cr3t", null, null), "8.3.27", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            String m = e.getMessage();
            assertTrue(m, m.contains("'demo_db'"));
            assertTrue(m, m.contains("'Demo'"));
            assertTrue(m, m.contains("уничтожила бы"));
            assertTrue(m, m.contains("rac infobase drop"));
            assertTrue(m, m.contains("--drop-database"));
            assertTrue(m, m.contains("dbName"));
        }
    }

    /** Проверка не прошла (например, у существующей базы есть пользователи) — тоже отказ, причина без паролей. */
    @Test
    public void createServerInfobase_probeFailure_refusedWithMaskedReason() {
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"), v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"),
            serverFactory(new ArrayList<>(), 1, null,
                "Пользователь ИБ не идентифицирован (Srvr=localhost;DBPwd=S3cr3tPw; кластер Cl0sterPw)"));
        try {
            cli.createServerInfobase(serverParams("S3cr3tPw", "admin", "Cl0sterPw"), "8.3.27", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            String m = e.getMessage();
            assertTrue(m, m.contains("Пользователь ИБ не идентифицирован"));
            assertTrue(m, m.contains("'demo_db'"));
            assertFalse(m, m.contains("S3cr3tPw"));
            assertFalse(m, m.contains("Cl0sterPw"));
        }
    }

    @Test
    public void createServerInfobase_probeWithoutDumpFile_refused() {
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"), v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"),
            serverFactory(new ArrayList<>(), 0, null, ""));
        try {
            cli.createServerInfobase(serverParams(null, null, null), "8.3.27", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ConfigDumpInfo.xml"));
            assertTrue(e.getMessage(), e.getMessage().contains("--drop-database"));
        }
    }

    /**
     * Ревью fix round 2: проверка не должна пропускать непонятный ответ. Пустой, обрезанный или без
     * {@code ConfigVersions} файл — такой же отказ, как найденная конфигурация (fail-closed).
     */
    @Test
    public void createServerInfobase_malformedProbeDump_refused() {
        List<String> malformed = List.of(
            "",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ConfigDumpInfo xmlns=\"http://v8.1c.ru/8.3/xcf/dumpinfo\">\n"
                + "\t<ConfigVersions>\n\t\t<Meta",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ConfigDumpInfo xmlns=\"http://v8.1c.ru/8.3/xcf/dumpinfo\">\n"
                + "</ConfigDumpInfo>\n");
        for (String dump : malformed) {
            RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"), v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"),
                serverFactory(new ArrayList<>(), 0, dump, ""));
            try {
                cli.createServerInfobase(serverParams(null, null, null), "8.3.27", Duration.ofSeconds(60));
                fail("expected ToolException for dump: " + dump);
            } catch (ToolException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("ConfigDumpInfo.xml"));
                assertTrue(e.getMessage(), e.getMessage().contains("--drop-database"));
            }
        }
    }

    /** Файловой базе проверка не нужна: каталог и так обязан быть пустым или отсутствовать. */
    @Test
    public void createFileInfobase_runsNoProbe() throws Exception {
        List<List<String>> captured = new ArrayList<>();
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"), (cmd, dir) -> { captured.add(cmd); return exitedWith(0); });

        cli.createFileInfobase(Paths.get("C:/tmp/IB"), "8.3.27", Duration.ofSeconds(60));

        assertEquals(1, captured.size());
    }

    /**
     * r2: пароль СУБД — подстрока пароля кластера. Короткий, вырезанный первым, оставил бы хвост
     * длинного («***Longer»); вырезать надо с самого длинного.
     */
    @Test
    public void createServerInfobase_literalSecrets_longestMaskedFirst() {
        Process p = failedWithStderr("FATAL: кластер отверг пароль S3cr3tLonger");
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"), (cmd, dir) -> p);
        try {
            cli.createServerInfobase(serverParams("S3cr3t", "admin", "S3cr3tLonger"), "8.3.27", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            String m = e.getMessage();
            assertFalse(m, m.contains("S3cr3t"));
            assertFalse("от длинного пароля не должно остаться хвоста: " + m, m.contains("Longer"));
            assertTrue(m, m.contains("кластер отверг пароль ***"));
        }
    }

    @Test
    public void createServerInfobase_invalidValue_rejectedBeforeProcess() {
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> { throw new IllegalStateException("not called"); },
            (c, d) -> { throw new IllegalStateException("not called"); });
        ServerInfobaseParams params = new ServerInfobaseParams("localhost", "Demo", "PostgreSQL",
            "localhost", "demo_db", "postgres", "se cret", true, true, null, null);
        try {
            cli.createServerInfobase(params, "8.3.27", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("dbPassword"));
        }
    }

    @Test
    public void createFileInfobase_failure_reportsOutLogText() {
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"),
            (cmd, dir) -> {
                java.nio.file.Path log = Paths.get(cmd.get(cmd.indexOf("/Out") + 1));
                Files.write(log, "Каталог информационной базы не пуст".getBytes(StandardCharsets.UTF_8));
                return exitedWith(1);
            });
        try {
            cli.createFileInfobase(Paths.get("C:/tmp/IB"), "8.3.27", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Каталог информационной базы не пуст"));
        }
    }

    @Test
    public void createFileInfobase_failure_masksDbPasswordInOutLog() {
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"),
            (cmd, dir) -> {
                java.nio.file.Path log = Paths.get(cmd.get(cmd.indexOf("/Out") + 1));
                Files.write(log, "... DBPwd=S3cr3t;DB=x ...".getBytes(StandardCharsets.UTF_8));
                return exitedWith(1);
            });
        try {
            cli.createFileInfobase(Paths.get("C:/tmp/IB"), "8.3.27", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("DBPwd=***"));
            assertFalse(e.getMessage(), e.getMessage().contains("S3cr3t"));
        }
    }

    /**
     * Каноническая форма строки подключения 1С берёт значения в кавычки: {@code DBPwd="…"}. Шаблон
     * «до первой кавычки» совпадал с пустой строкой и оставлял пароль в тексте целиком.
     */
    @Test
    public void createFileInfobase_failure_masksQuotedAndPlainPasswordsInOutLog() {
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"),
            (cmd, dir) -> {
                java.nio.file.Path log = Paths.get(cmd.get(cmd.indexOf("/Out") + 1));
                Files.write(log, "Srvr=localhost;Ref=Demo;DBPwd=\"p@ss w0rd\";SPwd=abc;DB=x"
                    .getBytes(StandardCharsets.UTF_8));
                return exitedWith(1);
            });
        try {
            cli.createFileInfobase(Paths.get("C:/tmp/IB"), "8.3.27", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            String m = e.getMessage();
            assertFalse(m, m.contains("p@ss"));
            assertFalse(m, m.contains("w0rd"));
            assertFalse(m, m.contains("SPwd=abc"));
            assertTrue(m, m.contains("DBPwd=***"));
            assertTrue(m, m.contains("SPwd=***"));
            assertTrue("остальной текст не тронут: " + m, m.contains("Ref=Demo") && m.contains("DB=x"));
        }
    }

    /** Причина из stderr (лог /Out пуст), пароли СУБД и кластера — вне формы «ключ=значение». */
    @Test
    public void createServerInfobase_failure_masksLiteralPasswordsInStderr() {
        Process p = failedWithStderr("FATAL: password authentication failed: S3cr3tPw; кластер: Cl0sterPw отклонён");
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"), (cmd, dir) -> p);
        ServerInfobaseParams params = new ServerInfobaseParams("localhost", "Demo", "PostgreSQL",
            "localhost", "demo_db", "postgres", "S3cr3tPw", true, true, "admin", "Cl0sterPw");
        try {
            cli.createServerInfobase(params, "8.3.27", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            String m = e.getMessage();
            assertFalse(m, m.contains("S3cr3tPw"));
            assertFalse(m, m.contains("Cl0sterPw"));
            assertTrue("причина из stderr дошла: " + m, m.contains("password authentication failed"));
        }
    }

    /**
     * Короткий пароль (до 3 символов) буквально не вырезается — это изуродовало бы весь текст;
     * его закрывает шаблон «ключ=значение».
     */
    @Test
    public void createServerInfobase_shortPassword_maskedOnlyAsKeyValue() {
        Process p = failedWithStderr("строка DBPwd=abc; каталог abcdef не найден");
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"), (cmd, dir) -> p);
        ServerInfobaseParams params = new ServerInfobaseParams("localhost", "Demo", "PostgreSQL",
            "localhost", "demo_db", "postgres", "abc", true, true, null, null);
        try {
            cli.createServerInfobase(params, "8.3.27", Duration.ofSeconds(60));
            fail("expected ToolException");
        } catch (ToolException e) {
            String m = e.getMessage();
            assertTrue(m, m.contains("DBPwd=***"));
            assertTrue("короткий пароль не вырезается из прочего текста: " + m, m.contains("каталог abcdef"));
        }
    }

    private static Process failedWithStderr(String stderr) {
        Process p = mock(Process.class);
        try {
            when(p.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        when(p.exitValue()).thenReturn(1);
        when(p.getErrorStream()).thenReturn(new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8)));
        when(p.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return p;
    }

    @Test
    public void createFileInfobase_fullBuildVersion_isAccepted() throws Exception {
        List<String> resolved = new ArrayList<>();
        RuntimeCli cli = new RuntimeCli(registryWith("8.3.27"),
            v -> { resolved.add(v); return new File("C:/1cv8/8.3.27.2214/bin/1cv8.exe"); },
            (cmd, dir) -> exitedWith(0));

        assertEquals(0, cli.createFileInfobase(Paths.get("C:/tmp/IB"), "8.3.27.2214", Duration.ofSeconds(60)));
        assertEquals(List.of("8.3.27.2214"), resolved);
    }
}
