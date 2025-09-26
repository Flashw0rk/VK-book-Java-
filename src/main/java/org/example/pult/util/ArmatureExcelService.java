package org.example.pult.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.pult.RowDataDynamic;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class ArmatureExcelService extends ExcelService {

    public ArmatureExcelService(String filePath) {
        super(filePath);
    }

    @Override
    public List<RowDataDynamic> readAllRows(String sheetName) throws IOException {
        List<RowDataDynamic> rows = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(new File(filePath));
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IOException("Лист с именем '" + sheetName + "' не найден в файле Excel: " + filePath);
            }

            List<String> headers = readHeaders(sheetName);
            List<CellRangeAddress> mergedRegions = sheet.getMergedRegions();

            Iterator<Row> rowIterator = sheet.iterator();
            if (rowIterator.hasNext()) {
                rowIterator.next(); // Пропускаем строку заголовков
            }

            while (rowIterator.hasNext()) {
                Row currentRow = rowIterator.next();
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = currentRow.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    String cellValue = getMergedCellValue(cell, mergedRegions);
                    rowMap.put(headers.get(i), cellValue);
                }
                rows.add(new RowDataDynamic(rowMap));
            }
        }
        return rows;
    }

    /**
     * Обновляет/записывает значение в колонке PDF_Схема_и_ID_арматуры для строки с указанной арматурой.
     * Возвращает true, если запись была найдена и обновлена.
     */
    public boolean updatePdfLink(String sheetName, String armatureName, String pdfFileName) throws IOException {
        File file = new File(filePath);
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw new IOException("Лист '" + sheetName + "' не найден: " + filePath);

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new IOException("Отсутствует строка заголовков в листе '" + sheetName + "'");

            int armCol = -1;
            int linkCol = -1;
            for (Cell c : headerRow) {
                String h = c.getStringCellValue();
                if ("Арматура".equalsIgnoreCase(h) || "Обозначение".equalsIgnoreCase(h)) armCol = c.getColumnIndex();
                if ("PDF_Схема_и_ID_арматуры".equalsIgnoreCase(h) || "PDF_Схема".equalsIgnoreCase(h)) linkCol = c.getColumnIndex();
            }
            if (armCol < 0) throw new IOException("Колонка 'Арматура' не найдена в листе '" + sheetName + "'");
            if (linkCol < 0) {
                linkCol = headerRow.getLastCellNum();
                Cell newHeader = headerRow.createCell(linkCol, CellType.STRING);
                newHeader.setCellValue("PDF_Схема_и_ID_арматуры");
                sheet.setColumnHidden(linkCol, true); // оставим скрытой
            }

            DataFormatter fmt = new DataFormatter();
            boolean updated = false;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Cell armCell = row.getCell(armCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                String name = fmt.formatCellValue(armCell);
                if (name != null && name.trim().equals(armatureName.trim())) {
                    Cell linkCell = row.getCell(linkCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    linkCell.setCellValue(pdfFileName);
                    updated = true;
                    break;
                }
            }

            if (updated) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    workbook.write(fos);
                }
            }
            return updated;
        }
    }

    /**
     * Очищает значение в колонке PDF_Схема_и_ID_арматуры для указанной арматуры.
     * Возвращает true, если запись была найдена и очищена.
     */
    public boolean clearPdfLink(String sheetName, String armatureName) throws IOException {
        File file = new File(filePath);
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) throw new IOException("Лист '" + sheetName + "' не найден: " + filePath);

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new IOException("Отсутствует строка заголовков в листе '" + sheetName + "'");

            int armCol = -1;
            int linkCol = -1;
            for (Cell c : headerRow) {
                String h = c.getStringCellValue();
                if ("Арматура".equalsIgnoreCase(h) || "Обозначение".equalsIgnoreCase(h)) armCol = c.getColumnIndex();
                if ("PDF_Схема_и_ID_арматуры".equalsIgnoreCase(h) || "PDF_Схема".equalsIgnoreCase(h)) linkCol = c.getColumnIndex();
            }
            if (armCol < 0) throw new IOException("Колонка 'Арматура' не найдена в листе '" + sheetName + "'");
            if (linkCol < 0) {
                // Колонка ещё не создавалась — очищать нечего
                return false;
            }

            DataFormatter fmt = new DataFormatter();
            boolean cleared = false;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Cell armCell = row.getCell(armCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                String name = fmt.formatCellValue(armCell);
                if (name != null && name.trim().equals(armatureName.trim())) {
                    Cell linkCell = row.getCell(linkCol, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    String prev = fmt.formatCellValue(linkCell);
                    if (prev != null && !prev.isEmpty()) {
                        linkCell.setBlank();
                        cleared = true;
                    }
                    break;
                }
            }

            if (cleared) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    workbook.write(fos);
                }
            }
            return cleared;
        }
    }
}