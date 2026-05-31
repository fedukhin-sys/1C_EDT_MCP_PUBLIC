package ru.fedukhin.edt.mcp.tools.form.internal;

import java.util.Map;

/**
 * BUG-06 follow-up: генерация BSL-stub'а для form event handler'а.
 *
 * <p>На вход — EDT-API имя события (e.g. {@code OnCreateAtServer}). На выходе —
 * BSL-фрагмент с правильной аннотацией ({@code &НаСервере} / {@code &НаКлиенте})
 * и стандартной сигнатурой параметров (русские имена для Russian-конфигураций).
 *
 * <p>Неизвестные события (item-level handlers с произвольными именами в EDT)
 * получают fallback {@code &НаКлиенте Процедура Name(Элемент) КонецПроцедуры} —
 * самая частая сигнатура item-обработчика.
 *
 * <p>Stub-тело — комментарий {@code // TODO: implement} (минимально, чтобы не мешал
 * Xtext'у и не вызывал warning'и unused variable).
 */
public final class FormHandlerStubFactory {

    /**
     * Сигнатура: список русских имён параметров. Пустой = процедура без параметров.
     */
    private static record EventSpec(boolean server, String[] params) {}

    /**
     * Whitelist частых form-level и item-level events. Содержит и form-module
     * события (OnCreateAtServer, OnOpen и т.п.), и item-level (OnChange, Click).
     * Неизвестные имена — fallback на {@link #ITEM_DEFAULT}.
     */
    private static final Map<String, EventSpec> EVENTS = Map.ofEntries(
        // ─── Form module — server side ───
        Map.entry("OnCreateAtServer",                       srv("Отказ", "СтандартнаяОбработка")),
        Map.entry("OnReadAtServer",                         srv("ТекущийОбъект")),
        Map.entry("BeforeWriteAtServer",                    srv("Отказ", "ТекущийОбъект", "ПараметрыЗаписи")),
        Map.entry("OnWriteAtServer",                        srv("ТекущийОбъект", "ПараметрыЗаписи")),
        Map.entry("AfterWriteAtServer",                     srv("ТекущийОбъект", "ПараметрыЗаписи")),
        Map.entry("FillCheckProcessingAtServer",            srv("Отказ", "ПроверяемыеРеквизиты")),
        Map.entry("BeforeLoadDataFromSettingsAtServer",     srv("Настройки")),
        Map.entry("OnLoadDataFromSettingsAtServer",         srv("Настройки")),
        Map.entry("BeforeSaveDataInSettingsAtServer",       srv("Настройки")),
        Map.entry("OnLoadUserSettingsAtServer",             srv("ПользовательскиеНастройки")),
        Map.entry("OnSaveUserSettingsAtServer",             srv("ПользовательскиеНастройки")),
        Map.entry("BeforeLoadVariantAtServer",              srv("НастройкиВарианта")),
        Map.entry("OnLoadVariantAtServer",                  srv("Настройки")),

        // ─── Form module — client side ───
        Map.entry("OnOpen",                                 cli("Отказ")),
        Map.entry("OnClose",                                cli("ЗавершениеРаботы")),
        Map.entry("BeforeClose",                            cli("Отказ", "ЗавершениеРаботы", "ТекстПредупреждения", "СтандартнаяОбработка")),
        Map.entry("BeforeWrite",                            cli("Отказ", "ПараметрыЗаписи")),
        Map.entry("OnWrite",                                cli("ПараметрыЗаписи")),
        Map.entry("AfterWrite",                             cli("ПараметрыЗаписи")),
        Map.entry("NotificationProcessing",                 cli("ИмяСобытия", "Параметр", "Источник")),
        Map.entry("ChoiceProcessing",                       cli("ВыбранноеЗначение", "ИсточникВыбора")),
        Map.entry("URLProcessing",                          cli("Элемент", "ФорматированнаяСтрока", "СтандартнаяОбработка")),
        Map.entry("ExternalEvent",                          cli("Источник", "Событие", "Данные")),

        // ─── Item-level (most common) ───
        Map.entry("OnChange",                               cli("Элемент")),
        Map.entry("OnEditEnd",                              cli("Элемент", "НовыеДанные", "ОтменаРедактирования")),
        Map.entry("BeforeRowChange",                        cli("Элемент", "Отказ")),
        Map.entry("OnActivateRow",                          cli("Элемент")),
        Map.entry("OnActivate",                             cli("Элемент")),
        Map.entry("OnStartEdit",                            cli("Элемент", "НоваяСтрока", "Копирование")),
        Map.entry("OnEndEdit",                              cli("Элемент", "НоваяСтрока", "ОтменаРедактирования")),
        Map.entry("Click",                                  cli("Элемент", "СтандартнаяОбработка")),
        Map.entry("AutoComplete",                           cli("Элемент", "Текст", "ДанныеВыбора", "ПараметрыПолученияДанных", "Ожидание", "СтандартнаяОбработка")),
        Map.entry("ChoiceStart",                            cli("Элемент", "ДанныеВыбора", "СтандартнаяОбработка")),
        Map.entry("Choice",                                 cli("Элемент", "ВыбранноеЗначение", "СтандартнаяОбработка")),
        Map.entry("ClearingTextStart",                      cli("Элемент", "СтандартнаяОбработка")),
        Map.entry("EditingTextChange",                      cli("Элемент", "Текст", "СтандартнаяОбработка")),
        Map.entry("Tuning",                                 cli("Элемент", "Направление", "СтандартнаяОбработка")),

        // ─── Commands ───
        Map.entry("Command",                                cli("Команда"))
    );

    private static final EventSpec ITEM_DEFAULT = cli("Элемент");

    private FormHandlerStubFactory() {}

    /**
     * Возвращает BSL-stub для процедуры с заданным именем, привязанной к событию
     * {@code event}. Включает аннотацию и стандартные параметры. Хвостовой
     * перевод строки гарантирован.
     */
    public static String stub(String event, String handlerName) {
        EventSpec spec = EVENTS.getOrDefault(event, ITEM_DEFAULT);
        StringBuilder sb = new StringBuilder();
        sb.append(spec.server() ? "&НаСервере" : "&НаКлиенте").append('\n');
        sb.append("Процедура ").append(handlerName).append('(');
        for (int i = 0; i < spec.params().length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(spec.params()[i]);
        }
        sb.append(")\n");
        sb.append("\t// TODO: implement\n");
        sb.append("КонецПроцедуры\n");
        return sb.toString();
    }

    /** {@code true} если событие — серверное (требует {@code &НаСервере}). */
    public static boolean isServerEvent(String event) {
        EventSpec spec = EVENTS.get(event);
        return spec != null && spec.server();
    }

    private static EventSpec srv(String... params) { return new EventSpec(true,  params); }
    private static EventSpec cli(String... params) { return new EventSpec(false, params); }
}
