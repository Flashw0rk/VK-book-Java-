package org.example.pult.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.pult.RowDataDynamic;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
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
}