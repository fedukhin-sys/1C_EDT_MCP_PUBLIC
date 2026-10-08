package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.infobase.internal.HeadlessInfobaseChangesResolver;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;

/**
 * Классы, которые биндятся в Guice-модуле {@code ToolsInfobaseModule}, должны загружаться,
 * линковаться (верификация), инициализироваться и отдавать рефлексией конструкторы, методы и
 * поля даже там, где API синхронизации v2 (пакет {@code ...infobases.sync.v2.*} и
 * {@code IInfobaseChangesResolver}) нет — как на ветках 1C:EDT старше 2026.1. Инжектор у бандла
 * один на все инструменты: споткнись он на одном классе — перестанут работать и
 * {@code deploy_project}, и {@code create_infobase}.
 *
 * <p><b>Что именно грузит тип.</b> Не любое упоминание в байткоде. Верификатор загружает тип,
 * когда проверяет присваиваемость: значение передаётся, возвращается или сохраняется туда, где
 * ожидается тип с ДРУГИМ именем (например, {@code HeadlessInfobaseChangesResolver} в параметр
 * {@code IInfobaseChangesResolver}), — ему нужно знать, интерфейс ли это и кто чей наследник.
 * Инструкции {@code checkcast}/{@code invoke*}/{@code ldc} с типом резолвят его лениво, при
 * первом исполнении. Guice при создании инжектора читает рефлексией сигнатуры конструкторов,
 * методов и полей — эти типы тоже грузятся. Ровно это тест и воспроизводит: инициализация класса
 * (с верификацией всех его методов) плюс {@code getDeclaredConstructors/Methods/Fields} и их
 * generic-сигнатуры.
 *
 * <p><b>Как.</b> {@link HidingClassLoader} заново определяет по байткоду ВСЕ классы бандла
 * {@code ru.fedukhin.edt.mcp.tools.infobase} — как их видел бы загрузчик бандла на старой EDT:
 * если верификация биндящегося класса потянет, например, {@code HeadlessInfobaseChangesResolver},
 * он тоже определится здесь и упадёт на отсутствующем интерфейсе, а не подтянется уже
 * слинкованным у host. Пакет {@code sync.v2.*} и {@code IInfobaseChangesResolver} загрузчик прячет
 * ({@code ClassNotFoundException}), всё остальное делегирует host. Список биндящихся классов берётся
 * из пула констант {@code ToolsInfobaseModule} (class-литералы {@code bind(X.class)}), так что новый
 * инструмент попадает под проверку без правки теста. Контроль: {@code HeadlessInfobaseChangesResolver},
 * реализующий спрятанный интерфейс, через тот же загрузчик не грузится — значит, прятанье работает.
 */
public class ProjectFromInfobaseUpdaterLinkageTest {

    private static final String BUNDLE_PREFIX = "ru.fedukhin.edt.mcp.tools.infobase.";
    private static final String MODULE = "ru/fedukhin/edt/mcp/tools/infobase/di/ToolsInfobaseModule.class";

    /**
     * Спрятано: API синхронизации v2 и — L8, fix round 4/4b — весь пакет {@code dt.core.resource}
     * ({@code IResourceStoreManager}, {@code EdtResourceMetadata}): методы подписей ресурсов —
     * default-методы, добавленные позже самого интерфейса, службу на старой ветке никто не проверял, и
     * класса значений там может не быть. Служба берётся лениво ({@code ServiceAccess} в момент вызова), а
     * снимки подписей сравниваются через {@code Objects.equals}, не называя классы пакета в сигнатурах.
     */
    private static final String[] BLOCKED_PREFIXES = {
        "com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.",
        "com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseChangesResolver",
        "com._1c.g5.v8.dt.core.resource.",
    };

    /**
     * Что биндится в модуле на момент написания теста — проверка, что разбор пула констант не
     * потерял классы (пустой список сделал бы главный тест вечнозелёным).
     */
    private static final Set<String> KNOWN_BOUND = Set.of(
        "ru.fedukhin.edt.mcp.tools.infobase.AssociateInfobaseTool",
        "ru.fedukhin.edt.mcp.tools.infobase.CreateInfobaseFromDtTool",
        "ru.fedukhin.edt.mcp.tools.infobase.CreateInfobaseTool",
        "ru.fedukhin.edt.mcp.tools.infobase.DeployProjectTool",
        "ru.fedukhin.edt.mcp.tools.infobase.GetInfobaseSyncStateTool",
        "ru.fedukhin.edt.mcp.tools.infobase.GetInfobaseTool",
        "ru.fedukhin.edt.mcp.tools.infobase.ListInfobasesTool",
        "ru.fedukhin.edt.mcp.tools.infobase.RestoreInfobaseFromDtTool",
        "ru.fedukhin.edt.mcp.tools.infobase.UpdateExtensionsFromCfeTool",
        "ru.fedukhin.edt.mcp.tools.infobase.UpdateProjectFromInfobaseTool",
        "ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncStateJobs",
        "ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseDeployer",
        "ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseJobs",
        "ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry",
        "ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets",
        "ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectFromInfobaseUpdater",
        "ru.fedukhin.edt.mcp.tools.infobase.internal.RuntimeCli",
        "ru.fedukhin.edt.mcp.tools.infobase.internal.SyncStateProbe",
        "ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2",
        "ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps");

    @Test
    public void boundClassList_isReadFromToolsInfobaseModule() throws Exception {
        Set<String> bound = classesBoundInModule();

        assertTrue("разбор ToolsInfobaseModule потерял классы: " + bound, bound.containsAll(KNOWN_BOUND));
        assertTrue("HeadlessInfobaseChangesResolver в Guice биндиться не должен: " + bound,
            !bound.contains(HeadlessInfobaseChangesResolver.class.getName()));
    }

    @Test
    public void everyGuiceBoundClass_linksWithoutSyncV2Api() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String className : classesBoundInModule()) {
            try {
                linkAndReflect(className);
            } catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
                // RuntimeException — например, TypeNotPresentException из generic-сигнатуры.
                failures.add(className + ": " + e);
            }
        }
        assertEquals("классы ToolsInfobaseModule не линкуются без API синхронизации v2 на classpath",
            List.of(), failures);
    }

    @Test
    public void projectFromInfobaseUpdater_linksWithoutSyncV2Api() throws Exception {
        linkAndReflect(ProjectFromInfobaseUpdater.class.getName());
    }

    @Test
    public void syncV2_linksWithoutSyncV2Api() throws Exception {
        linkAndReflect(SyncV2.class.getName());
    }

    /** Контроль: без него зелёный главный тест мог бы означать лишь, что прятанье не работает. */
    @Test
    public void hidingLoader_reallyHidesSyncV2Api() throws Exception {
        HidingClassLoader hiding = new HidingClassLoader(host());
        try {
            Class.forName(HeadlessInfobaseChangesResolver.class.getName(), true, hiding);
            fail("HeadlessInfobaseChangesResolver реализует спрятанный IInfobaseChangesResolver и не должен был загрузиться");
        } catch (NoClassDefFoundError expected) {
            assertTrue(String.valueOf(expected.getMessage()),
                String.valueOf(expected.getMessage()).contains("IInfobaseChangesResolver"));
        }
    }

    /** Контроль прятанья пакета {@code dt.core.resource}. */
    @Test
    public void hidingLoader_reallyHidesResourceStoreApi() {
        for (String name : List.of("com._1c.g5.v8.dt.core.resource.EdtResourceMetadata",
                "com._1c.g5.v8.dt.core.resource.IResourceStoreManager")) {
            try {
                Class.forName(name, false, new HidingClassLoader(host()));
                fail(name + " должен быть спрятан");
            } catch (ClassNotFoundException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(name));
            }
        }
    }

    /**
     * Fix round 4b: службы, которой может не быть на ветке EDT, в Guice нет — ни привязки в модуле, ни
     * параметра {@code @Inject}-конструктора. Иначе создание {@code ProjectFromInfobaseUpdater} упало бы, и
     * четыре инструмента синхронизации молча пропали бы из tools/list вместо ответа «требуется 1C:EDT 2026.1
     * или новее» (спека 6.4). В конструкторе — только службы, которые есть во всех ветках.
     */
    @Test
    public void guiceConstructorAndModule_doNotRequireResourceStoreService() throws Exception {
        List<Constructor<?>> injected = new ArrayList<>();
        for (Constructor<?> c : ProjectFromInfobaseUpdater.class.getConstructors()) {
            for (java.lang.annotation.Annotation a : c.getAnnotations()) {
                if (a.annotationType().getName().equals("jakarta.inject.Inject")) injected.add(c);
            }
        }
        assertEquals("ровно один @Inject-конструктор", 1, injected.size());
        List<String> parameters = new ArrayList<>();
        for (Class<?> type : injected.get(0).getParameterTypes()) parameters.add(type.getName());
        assertEquals(List.of(
            "com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager",
            SyncV2.class.getName(),
            "com._1c.g5.v8.dt.core.platform.IV8ProjectManager",
            "com._1c.g5.v8.dt.core.platform.IBmModelManager",
            // Fix round 7: свой класс бандла (биндится в модуле), в его сигнатурах — публичный API всех веток.
            "ru.fedukhin.edt.mcp.tools.infobase.internal.ThickClientOps",
            // Fix round 8: свой класс бандла; задание EDT он узнаёт по имени класса строкой.
            "ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncStateJobs"), parameters);
        try (InputStream in = ProjectFromInfobaseUpdater.class.getClassLoader().getResourceAsStream(MODULE)) {
            assertNotNull("не найден байткод " + MODULE, in);
            for (String name : classConstants(new DataInputStream(in))) {
                assertTrue("ToolsInfobaseModule не должен биндить " + name,
                    !name.startsWith("com/_1c/g5/v8/dt/core/resource/"));
            }
        }
    }

    /**
     * Свежий загрузчик на каждый класс: загрузка, верификация и инициализация, затем то, что
     * делает Guice, — конструкторы, методы, поля и их generic-сигнатуры.
     */
    private static void linkAndReflect(String className) throws ClassNotFoundException {
        Class<?> loaded = Class.forName(className, true, new HidingClassLoader(host()));
        assertEquals("класс обязан определиться заново, а не прийти от host: " + className,
            HidingClassLoader.class, loaded.getClassLoader().getClass());
        for (Constructor<?> c : loaded.getDeclaredConstructors()) {
            c.getGenericParameterTypes();
        }
        for (Method m : loaded.getDeclaredMethods()) {
            m.getGenericParameterTypes();
            m.getGenericReturnType();
        }
        for (Field f : loaded.getDeclaredFields()) {
            f.getGenericType();
        }
    }

    private static ClassLoader host() {
        return ProjectFromInfobaseUpdaterLinkageTest.class.getClassLoader();
    }

    /**
     * Классы бандла, на которые ссылаются class-литералы {@code ToolsInfobaseModule} (кроме пакета
     * {@code di}), — то есть всё, что модуль биндит. Байткод модуля читается загрузчиком самого
     * бандла: пакет {@code di} не экспортируется.
     */
    private static Set<String> classesBoundInModule() throws IOException {
        try (InputStream in = ProjectFromInfobaseUpdater.class.getClassLoader().getResourceAsStream(MODULE)) {
            assertNotNull("не найден байткод " + MODULE, in);
            Set<String> bound = new TreeSet<>();
            for (String internalName : classConstants(new DataInputStream(in))) {
                String name = internalName.replace('/', '.');
                if (name.startsWith(BUNDLE_PREFIX) && !name.startsWith(BUNDLE_PREFIX + "di.")) bound.add(name);
            }
            return bound;
        }
    }

    /** Имена всех {@code CONSTANT_Class} пула констант class-файла (JVMS §4.4). */
    private static List<String> classConstants(DataInputStream in) throws IOException {
        if (in.readInt() != 0xCAFEBABE) throw new IOException("не class-файл");
        in.readUnsignedShort(); // minor_version
        in.readUnsignedShort(); // major_version
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();                          // Utf8
                case 7 -> classNameIndex[i] = in.readUnsignedShort();      // Class
                case 8, 16, 19, 20 -> in.readUnsignedShort();              // String, MethodType, Module, Package
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); } // MethodHandle
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();          // Integer, Float, *ref, NameAndType, (Invoke)Dynamic
                case 5, 6 -> { in.readLong(); i++; }                       // Long, Double — два слота
                default -> throw new IOException("неизвестный тег пула констант " + tag + " в слоте " + i);
            }
        }
        List<String> names = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            if (classNameIndex[i] != 0) names.add(utf8[classNameIndex[i]]);
        }
        return names;
    }

    /**
     * Загрузчик «бандл без API v2»: классы {@link #BUNDLE_PREFIX} определяет сам по байткоду host
     * (иначе вернулись бы уже слинкованные на этой, настоящей, EDT), {@link #BLOCKED_PREFIXES} —
     * {@code ClassNotFoundException} независимо от того, видит ли их host, остальное — host.
     */
    private static final class HidingClassLoader extends ClassLoader {
        private final ClassLoader host;

        HidingClassLoader(ClassLoader host) {
            super(host);
            this.host = host;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String blocked : BLOCKED_PREFIXES) {
                if (name.startsWith(blocked)) {
                    throw new ClassNotFoundException(name + " спрятан тестом (имитация 1C:EDT без API v2)");
                }
            }
            if (!name.startsWith(BUNDLE_PREFIX)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = defineFromHostBytes(name);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        private Class<?> defineFromHostBytes(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = host.getResourceAsStream(path)) {
                if (in == null) {
                    throw new ClassNotFoundException(name + ": host не отдал байткод по " + path);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
