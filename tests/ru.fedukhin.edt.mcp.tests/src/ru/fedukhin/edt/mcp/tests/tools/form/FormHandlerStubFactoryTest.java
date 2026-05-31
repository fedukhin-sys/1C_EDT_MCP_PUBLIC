package ru.fedukhin.edt.mcp.tests.tools.form;

import static org.junit.Assert.*;

import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.form.internal.FormHandlerStubFactory;

/**
 * BUG-06: stub procedure has the correct annotation ({@code &НаСервере}/{@code &НаКлиенте})
 * and the standard parameter signature for the bound event.
 */
public class FormHandlerStubFactoryTest {

    @Test public void onCreateAtServer_isServerWithCancelAndStandardProcessing() {
        String stub = FormHandlerStubFactory.stub("OnCreateAtServer", "ПриСозданииНаСервере");
        assertTrue(stub, stub.startsWith("&НаСервере\n"));
        assertTrue(stub, stub.contains("Процедура ПриСозданииНаСервере(Отказ, СтандартнаяОбработка)"));
        assertTrue(stub, stub.contains("КонецПроцедуры"));
        assertTrue(FormHandlerStubFactory.isServerEvent("OnCreateAtServer"));
    }

    @Test public void onOpen_isClientWithCancel() {
        String stub = FormHandlerStubFactory.stub("OnOpen", "ПриОткрытии");
        assertTrue(stub, stub.startsWith("&НаКлиенте\n"));
        assertTrue(stub, stub.contains("Процедура ПриОткрытии(Отказ)"));
        assertFalse(FormHandlerStubFactory.isServerEvent("OnOpen"));
    }

    @Test public void beforeWriteAtServer_hasThreeParams() {
        String stub = FormHandlerStubFactory.stub("BeforeWriteAtServer", "ПередЗаписьюНаСервере");
        assertTrue(stub, stub.contains("(Отказ, ТекущийОбъект, ПараметрыЗаписи)"));
        assertTrue(FormHandlerStubFactory.isServerEvent("BeforeWriteAtServer"));
    }

    @Test public void beforeClose_clientWithFourParams() {
        String stub = FormHandlerStubFactory.stub("BeforeClose", "ПередЗакрытием");
        assertTrue(stub, stub.startsWith("&НаКлиенте"));
        assertTrue(stub, stub.contains("(Отказ, ЗавершениеРаботы, ТекстПредупреждения, СтандартнаяОбработка)"));
    }

    @Test public void notificationProcessing_clientWithThreeParams() {
        String stub = FormHandlerStubFactory.stub("NotificationProcessing", "ОбработкаОповещения");
        assertTrue(stub, stub.contains("(ИмяСобытия, Параметр, Источник)"));
    }

    @Test public void itemOnChange_clientWithItem() {
        String stub = FormHandlerStubFactory.stub("OnChange", "ПолеНаименованиеПриИзменении");
        assertTrue(stub, stub.startsWith("&НаКлиенте"));
        assertTrue(stub, stub.contains("(Элемент)"));
    }

    @Test public void buttonClick_clientWithItemAndStandardProcessing() {
        String stub = FormHandlerStubFactory.stub("Click", "КнопкаНажатие");
        assertTrue(stub, stub.contains("(Элемент, СтандартнаяОбработка)"));
    }

    @Test public void command_clientWithCommandParam() {
        String stub = FormHandlerStubFactory.stub("Command", "МояКоманда");
        assertTrue(stub, stub.startsWith("&НаКлиенте"));
        assertTrue(stub, stub.contains("(Команда)"));
    }

    @Test public void unknownEvent_fallsBackToClientItemSignature() {
        String stub = FormHandlerStubFactory.stub("SomeUnknownEvent", "Обработчик");
        assertTrue(stub, stub.startsWith("&НаКлиенте"));
        assertTrue(stub, stub.contains("Процедура Обработчик(Элемент)"));
    }

    @Test public void stub_endsWithKonecProcedureAndNewline() {
        String stub = FormHandlerStubFactory.stub("OnOpen", "ПриОткрытии");
        assertTrue(stub.endsWith("КонецПроцедуры\n"));
    }

    @Test public void stub_containsTodoComment() {
        String stub = FormHandlerStubFactory.stub("OnOpen", "ПриОткрытии");
        assertTrue(stub, stub.contains("// TODO: implement"));
    }

    @Test public void isServerEvent_unknownIsFalse() {
        assertFalse(FormHandlerStubFactory.isServerEvent("Click"));
        assertFalse(FormHandlerStubFactory.isServerEvent("DoesNotExist"));
    }
}
