package org.example.pult.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.pult.RowDataDynamic;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сервис для чтения данных из Excel файлов.
 * Теперь является классом, инстанцируемым с путем к файлу.
 */
public class ExcelService {

    protected final String filePath;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy");

    public ExcelService(String filePath) {
        this.filePath = filePath;
    }

    /**
     * Читает заголовки (первую строку) указанного листа Excel.
     * @param sheetName Имя листа Excel.
     * @return Список строк, представляющих заголовки.
     * @throws IOException Если произошла ошибка ввода-вывода при чтении файла.
     */
    public List<String> readHeaders(String sheetName) throws IOException {
        List<String> headers = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(new File(filePath));
             Workbook workbook = new XSSFWorkbook(fis)) { // Используем XSSFWorkbook для .xlsx

            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IOException("Лист с именем '" + sheetName + "' не найден в файле Excel: " + filePath);
            }

            Row headerRow = sheet.getRow(0); // Предполагаем, что заголовки в первой строке (индекс 0)
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    headers.add(getCellValueAsString(cell));
                }
            }
        }
        return headers;
    }

    /**
     * Читает фактические ширины столбцов из Excel
     * @param sheetName Имя листа Excel
     * @return Map<Имя колонки, Ширина в пикселях>
     * @throws IOException Если произошла ошибка ввода-вывода
     */
    public Map<String, Integer> getColumnWidths(String sheetName) throws IOException {
        Map<String, Integer> widths = new LinkedHashMap<>();
        try (FileInputStream fis = new FileInputStream(new File(filePath));
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IOException("Лист с именем '" + sheetName + "' не найден");
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    String columnName = getCellValueAsString(cell);
                    int colIndex = cell.getColumnIndex();
                    int excelWidth = sheet.getColumnWidth(colIndex);
                    // Конвертируем excel-единицы в пиксели (1 excel unit ≈ 1/256 символа)
                    int pixelWidth = (int)(excelWidth * 7.0 / 256); // Эмпирический коэффициент
                    widths.put(columnName, Math.max(50, pixelWidth)); // Минимум 50px
                }
            }
        }
        return widths;
    }

    /**
     * Читает все строки данных (начиная со второй строки) из указанного листа Excel.
     * @param sheetName Имя листа Excel.
     * @return Список объектов RowDataDynamic, каждый из которых представляет строку данных.
     * @throws IOException Если произошла ошибка ввода-вывода при чтении файла.
     */
    public List<RowDataDynamic> readAllRows(String sheetName) throws IOException {
        List<RowDataDynamic> data = new ArrayList<>();
        List<String> headers = readHeaders(sheetName); // Сначала получаем заголовки

        try (FileInputStream fis = new FileInputStream(new File(filePath));
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IOException("Лист с именем '" + sheetName + "' не найден в файле Excel: " + filePath);
            }

            Iterator<Row> rowIterator = sheet.iterator();
            if (rowIterator.hasNext()) {
                rowIterator.next(); // Пропускаем строку заголовков
            }

            List<CellRangeAddress> mergedRegions = sheet.getMergedRegions(); // Получаем объединенные ячейки

            while (rowIterator.hasNext()) {
                Row currentRow = rowIterator.next();
                Map<String, String> rowMap = new LinkedHashMap<>(); // Для сохранения порядка
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = currentRow.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    String cellValue = getMergedCellValue(cell, mergedRegions); // Используем функцию для объединенных ячеек
                    rowMap.put(headers.get(i), cellValue);
                }
                data.add(new RowDataDynamic(rowMap));
            }
        }
        return data;
    }

    /**
     * Вспомогательный метод для получения строкового значения ячейки.
     * Обрабатывает различные типы ячеек.
     * @param cell Ячейка Excel.
     * @return Строковое представление содержимого ячейки.
     */
    protected String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return DATE_FORMAT.format(cell.getDateCellValue());
                } else {
                    // Используем DataFormatter для лучшей обработки чисел (включая научную нотацию)
                    DataFormatter formatter = new DataFormatter();
                    return formatter.formatCellValue(cell);
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                // Попытаемся вычислить формулу, если возможно
                try {
                    return cell.getStringCellValue(); // Может работать для формул, возвращающих строку
                } catch (IllegalStateException e) {
                    // Если не строка, то попробуем как число
                    return String.valueOf(cell.getNumericCellValue());
                }
            case BLANK:
                return "";
            default:
                return "";
        }
    }

    /**
     * Вспомогательный метод для получения значения ячейки с учетом объединенных областей.
     * @param cell Ячейка.
     * @param mergedRegions Список объединенных областей на листе.
     * @return Строковое значение ячейки.
     */
    protected String getMergedCellValue(Cell cell, List<CellRangeAddress> mergedRegions) {
        for (CellRangeAddress mergedRegion : mergedRegions) {
            if (mergedRegion.isInRange(cell.getRowIndex(), cell.getColumnIndex())) {
                Row firstRow = cell.getSheet().getRow(mergedRegion.getFirstRow());
                Cell firstCell = firstRow.getCell(mergedRegion.getFirstColumn());
                return getCellValueAsString(firstCell); // Используем тот же метод для получения значения из главной ячейки
            }
        }
        return getCellValueAsString(cell);
    }
}