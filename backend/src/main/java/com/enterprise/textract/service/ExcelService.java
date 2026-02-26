package com.enterprise.textract.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

@Slf4j
@Service
public class ExcelService {

    /**
     * Reads an Excel file (.xlsx) from the given InputStream and returns the
     * content of the first non-empty sheet as a list of row maps.
     * The first row is treated as the header row.
     */
    public List<Map<String, String>> excelToTableData(InputStream inputStream) {
        List<Map<String, String>> rows = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            // Try "Raw Text" sheet first, then Tables, then first sheet
            Sheet sheet = workbook.getSheet("Raw Text");
            if (sheet == null)
                sheet = workbook.getSheet("Tables");
            if (sheet == null && workbook.getNumberOfSheets() > 0) {
                sheet = workbook.getSheetAt(0);
            }

            if (sheet == null) {
                log.warn("No sheets found in Excel workbook");
                return rows;
            }

            List<String> headers = new ArrayList<>();
            Iterator<Row> rowIterator = sheet.iterator();

            // Read header row
            if (rowIterator.hasNext()) {
                Row headerRow = rowIterator.next();
                for (Cell cell : headerRow) {
                    headers.add(getCellValue(cell));
                }
            }

            // Read data rows
            while (rowIterator.hasNext()) {
                Row dataRow = rowIterator.next();
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = dataRow.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    rowMap.put(headers.get(i), cell != null ? getCellValue(cell) : "");
                }
                if (!rowMap.values().stream().allMatch(String::isBlank)) {
                    rows.add(rowMap);
                }
            }

            log.info("Parsed {} data rows from Excel sheet '{}'", rows.size(), sheet.getSheetName());
        } catch (Exception e) {
            log.error("Failed to parse Excel file", e);
            throw new RuntimeException("Failed to read Excel result file: " + e.getMessage(), e);
        }

        return rows;
    }

    /**
     * Returns all sheet names and their data for multi-sheet display.
     */
    public Map<String, List<Map<String, String>>> excelToAllSheets(InputStream inputStream) {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                List<String> headers = new ArrayList<>();
                List<Map<String, String>> sheetRows = new ArrayList<>();
                Iterator<Row> rowIterator = sheet.iterator();

                if (rowIterator.hasNext()) {
                    for (Cell cell : rowIterator.next()) {
                        headers.add(getCellValue(cell));
                    }
                }

                while (rowIterator.hasNext()) {
                    Row row = rowIterator.next();
                    Map<String, String> rowMap = new LinkedHashMap<>();
                    for (int i = 0; i < headers.size(); i++) {
                        Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        rowMap.put(headers.get(i), cell != null ? getCellValue(cell) : "");
                    }
                    if (!rowMap.values().stream().allMatch(String::isBlank)) {
                        sheetRows.add(rowMap);
                    }
                }

                result.put(sheet.getSheetName(), sheetRows);
            }
        } catch (Exception e) {
            log.error("Failed to parse Excel workbook", e);
            throw new RuntimeException("Failed to read Excel result file: " + e.getMessage(), e);
        }

        return result;
    }

    private String getCellValue(Cell cell) {
        if (cell == null)
            return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                yield val == Math.floor(val) ? String.valueOf((long) val) : String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }
}
