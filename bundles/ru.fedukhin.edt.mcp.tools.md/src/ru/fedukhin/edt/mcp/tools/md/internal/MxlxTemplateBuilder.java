package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Генератор макета печатной формы {@code .mxlx} (spreadsheet XML
 * {@code http://v8.1c.ru/8.2/data/spreadsheet}).
 *
 * <p>Java-порт проверенного Python-прототипа {@code scripts/gen_mxlx_dosudebka.py}
 * (вёрстка подтверждена PDF-рендером и ручной правкой пользователя в редакторе EDT,
 * см. memory external-processors-spike). Чистая функция: spec → XML-строка, без
 * зависимостей от Eclipse/EMF — тестируется юнит-тестом напрямую.
 *
 * <h3>Кодирование ячеек (выверено по эталону)</h3>
 * <ul>
 *   <li>Ячейка пишется в свою позицию по порядку (col0, col1 …) <b>БЕЗ</b> {@code <i>}.
 *       {@code <i>N</i>} = индекс колонки для разреженных строк, НЕ span — ставить его
 *       для объединения нельзя (контент уезжает в несуществующую колонку).</li>
 *   <li>Объединение (span&gt;1 или rowSpan&gt;1) задаётся отдельной записью
 *       {@code <merge><r>строка0based</r><c>колонка0based</c><w>доп.колонок</w>[<h>доп.строк</h>]</merge>}
 *       ПОСЛЕ {@code vgRows}, ПЕРЕД {@code namedItem}. {@code <w>} = span-1 (доп. колонки),
 *       {@code <h>} = rowSpan-1.</li>
 *   <li>Параметр в ячейке: {@code <parameter>Имя</parameter>}; формат ячейки должен иметь
 *       {@code <fillType>Parameter</fillType>}.</li>
 * </ul>
 *
 * <p>Форматы 1-based; первыми идут форматы ширин колонок (по одному на колонку),
 * затем по одному формату на каждую ячейку. Шрифты 0-based, дедуплицируются по
 * {@code (faceName, height, bold)}.
 */
public final class MxlxTemplateBuilder {

    public static final String DEFAULT_FACE = "Times New Roman";
    public static final int    DEFAULT_SIZE = 11;

    private static final String NS =
            "xmlns=\"http://v8.1c.ru/8.2/data/spreadsheet\" "
          + "xmlns:style=\"http://v8.1c.ru/8.1/data/ui/style\" "
          + "xmlns:v8=\"http://v8.1c.ru/8.1/data/core\" "
          + "xmlns:v8ui=\"http://v8.1c.ru/8.1/data/ui\" "
          + "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" "
          + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"";

    /** Одна ячейка макета. {@code text} XOR {@code parameter} (оба null ⇒ пустая ячейка). */
    public record Cell(String text, String parameter, int span, int rowSpan,
                       boolean bold, int fontSize, String hAlign, String vAlign, boolean wrap) {
        public Cell {
            if (span < 1)    span = 1;
            if (rowSpan < 1) rowSpan = 1;
            if (fontSize <= 0) fontSize = DEFAULT_SIZE;
            if (hAlign == null || hAlign.isEmpty()) hAlign = "Left";
            if (vAlign == null || vAlign.isEmpty()) vAlign = "Top";
        }
        boolean isParameter() { return parameter != null && !parameter.isEmpty(); }
    }

    /** Строка макета. */
    public record Row(List<Cell> cells) { }

    /** Шрифт — ключ дедупликации. */
    private record Font(String face, int height, boolean bold) { }

    /**
     * Строит .mxlx из спецификации.
     *
     * @param areaName     имя именованной области (для {@code ПолучитьОбласть}); если пусто — "Область1"
     * @param columnWidths ширины колонок (число колонок = размер списка); если null/пусто — одна колонка 800
     * @param rows         строки (если пусто — одна пустая строка с одной ячейкой)
     */
    public String build(String areaName, List<Integer> columnWidths, List<Row> rows) {
        if (areaName == null || areaName.isEmpty()) areaName = "Область1";
        List<Integer> widths = (columnWidths == null || columnWidths.isEmpty())
                ? List.of(800) : columnWidths;
        int ncols = widths.size();
        List<Row> body = (rows == null || rows.isEmpty())
                ? List.of(new Row(List.of(new Cell(null, null, 1, 1, false, DEFAULT_SIZE, "Left", "Top", true))))
                : rows;

        // --- Дедуп шрифтов (0-based) ---
        List<Font> fonts = new ArrayList<>();
        Map<Font, Integer> fontIdx = new LinkedHashMap<>();
        for (Row r : body) {
            for (Cell c : r.cells()) {
                Font f = new Font(DEFAULT_FACE, c.fontSize(), c.bold());
                if (!fontIdx.containsKey(f)) {
                    fontIdx.put(f, fonts.size());
                    fonts.add(f);
                }
            }
        }
        if (fonts.isEmpty()) { // на всякий
            Font f = new Font(DEFAULT_FACE, DEFAULT_SIZE, false);
            fontIdx.put(f, 0);
            fonts.add(f);
        }

        // --- Назначение индексов форматов (1-based): сначала ширины, потом по ячейке ---
        // widthFormatIndex[col] = col+1 ; cell formats начинаются с ncols+1.
        int nextFormat = ncols + 1;

        StringBuilder rowsXml  = new StringBuilder();
        List<int[]>   merges   = new ArrayList<>(); // {row, startCol, addCols, addRows}
        List<Cell>    flatCells = new ArrayList<>();

        for (int ri = 0; ri < body.size(); ri++) {
            Row r = body.get(ri);
            rowsXml.append("\t<rowsItem>\n\t\t<index>").append(ri).append("</index>\n\t\t<row>\n");
            int col = 0;
            for (Cell c : r.cells()) {
                int fmt = nextFormat++;
                flatCells.add(c);
                if (c.span() > 1 || c.rowSpan() > 1) {
                    merges.add(new int[]{ri, col, c.span() - 1, c.rowSpan() - 1});
                }
                col += c.span();
                rowsXml.append("\t\t\t<c>\n\t\t\t\t<c>\n\t\t\t\t\t<f>").append(fmt).append("</f>\n");
                if (c.isParameter()) {
                    rowsXml.append("\t\t\t\t\t<parameter>").append(esc(c.parameter())).append("</parameter>\n");
                } else if (c.text() != null && !c.text().isEmpty()) {
                    rowsXml.append("\t\t\t\t\t<tl>\n\t\t\t\t\t\t<v8:item>\n\t\t\t\t\t\t\t<v8:lang>ru</v8:lang>\n")
                           .append("\t\t\t\t\t\t\t<v8:content>").append(esc(c.text())).append("</v8:content>\n")
                           .append("\t\t\t\t\t\t</v8:item>\n\t\t\t\t\t</tl>\n");
                }
                rowsXml.append("\t\t\t\t</c>\n\t\t\t</c>\n");
            }
            rowsXml.append("\t\t</row>\n\t</rowsItem>\n");
        }

        StringBuilder o = new StringBuilder();
        o.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        o.append("<document ").append(NS).append(">\n");
        o.append("\t<languageSettings>\n\t\t<currentLanguage>ru</currentLanguage>\n")
         .append("\t\t<defaultLanguage>ru</defaultLanguage>\n\t\t<languageInfo>\n\t\t\t<id>ru</id>\n")
         .append("\t\t\t<code>Русский</code>\n\t\t\t<description>Русский</description>\n")
         .append("\t\t</languageInfo>\n\t</languageSettings>\n");

        // columns
        o.append("\t<columns>\n\t\t<size>").append(ncols).append("</size>\n");
        for (int ci = 0; ci < ncols; ci++) {
            o.append("\t\t<columnsItem>\n\t\t\t<index>").append(ci).append("</index>\n\t\t\t<column>\n")
             .append("\t\t\t\t<formatIndex>").append(ci + 1).append("</formatIndex>\n")
             .append("\t\t\t</column>\n\t\t</columnsItem>\n");
        }
        o.append("\t</columns>\n");

        // rows
        o.append(rowsXml);

        // template mode + defaults
        o.append("\t<templateMode>true</templateMode>\n");
        o.append("\t<defaultFormatIndex>").append(ncols + 1).append("</defaultFormatIndex>\n");
        o.append("\t<height>").append(body.size()).append("</height>\n")
         .append("\t<vgRows>").append(body.size()).append("</vgRows>\n");

        // merges (ПОСЛЕ vgRows, ПЕРЕД namedItem)
        for (int[] m : merges) {
            o.append("\t<merge>\n\t\t<r>").append(m[0]).append("</r>\n\t\t<c>").append(m[1])
             .append("</c>\n\t\t<w>").append(m[2]).append("</w>\n");
            if (m[3] > 0) o.append("\t\t<h>").append(m[3]).append("</h>\n");
            o.append("\t</merge>\n");
        }

        // namedItem
        o.append("\t<namedItem xsi:type=\"NamedItemCells\">\n\t\t<name>").append(esc(areaName)).append("</name>\n")
         .append("\t\t<area>\n\t\t\t<type>Rows</type>\n\t\t\t<beginRow>0</beginRow>\n")
         .append("\t\t\t<endRow>").append(body.size() - 1).append("</endRow>\n")
         .append("\t\t\t<beginColumn>-1</beginColumn>\n\t\t\t<endColumn>-1</endColumn>\n")
         .append("\t\t</area>\n\t</namedItem>\n");

        // printSettings (портрет A4, поля 500)
        o.append("\t<printSettings>\n\t\t<pageOrientation>Portrait</pageOrientation>\n\t\t<scale>100</scale>\n")
         .append("\t\t<collate>true</collate>\n\t\t<copies>1</copies>\n\t\t<perPage>1</perPage>\n")
         .append("\t\t<topMargin>500</topMargin>\n\t\t<leftMargin>500</leftMargin>\n")
         .append("\t\t<bottomMargin>500</bottomMargin>\n\t\t<rightMargin>500</rightMargin>\n")
         .append("\t\t<headerSize>200</headerSize>\n\t\t<footerSize>200</footerSize>\n")
         .append("\t\t<fitToPage>false</fitToPage>\n\t\t<blackAndWhite>false</blackAndWhite>\n")
         .append("\t\t<paper>9</paper>\n\t\t<pageWidth>0</pageWidth>\n\t\t<pageHeight>0</pageHeight>\n")
         .append("\t\t<duplexType>UsePrinterSettings</duplexType>\n")
         .append("\t\t<pagePlacementAlternation>Auto</pagePlacementAlternation>\n\t</printSettings>\n");

        // line
        o.append("\t<line width=\"1\" gap=\"false\">\n")
         .append("\t\t<v8ui:style xsi:type=\"v8ui:SpreadsheetDocumentCellLineType\">Solid</v8ui:style>\n\t</line>\n");

        // fonts (0-based, в порядке добавления)
        for (Font f : fonts) {
            o.append("\t<font faceName=\"").append(esc(f.face())).append("\" height=\"").append(f.height())
             .append("\" bold=\"").append(f.bold()).append("\" italic=\"false\" underline=\"false\" ")
             .append("strikeout=\"false\" kind=\"Absolute\" scale=\"100\"/>\n");
        }

        // formats: 1..ncols — ширины; далее — по ячейке
        for (int ci = 0; ci < ncols; ci++) {
            o.append("\t<format>\n\t\t<width>").append(widths.get(ci)).append("</width>\n\t</format>\n");
        }
        for (int i = 0; i < flatCells.size(); i++) {
            Cell c = flatCells.get(i);
            int fi = fontIdx.get(new Font(DEFAULT_FACE, c.fontSize(), c.bold()));
            o.append("\t<format>\n\t\t<font>").append(fi).append("</font>\n")
             .append("\t\t<horizontalAlignment>").append(c.hAlign()).append("</horizontalAlignment>\n")
             .append("\t\t<verticalAlignment>").append(c.vAlign()).append("</verticalAlignment>\n")
             .append("\t\t<textColor>#000000</textColor>\n")
             .append("\t\t<textPlacement>").append(c.wrap() ? "Wrap" : "Auto").append("</textPlacement>\n");
            if (c.isParameter()) {
                o.append("\t\t<fillType>Parameter</fillType>\n");
            }
            o.append("\t</format>\n");
        }

        o.append("</document>\n");
        return o.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
