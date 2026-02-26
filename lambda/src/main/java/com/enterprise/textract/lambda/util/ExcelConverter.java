package com.enterprise.textract.lambda.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.RelationshipType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts Textract response Blocks into a structured Excel workbook.
 * Sheet 1 — "Raw Text": All LINE blocks (plain extracted text, one line per
 * row).
 * Sheet 2 — "Key-Values": KEY_VALUE_SET blocks (form fields and their values).
 * Sheet 3 — "Tables": TABLE blocks formatted as grids.
 */
public class ExcelConverter {

    private static final Logger log = LoggerFactory.getLogger(ExcelConverter.class);

    private ExcelConverter() {
    }

    public static byte[] convert(List<Block> blocks, String originalFileName) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            // Build lookup map: id → block
            Map<String, Block> blockMap = blocks.stream()
                    .collect(Collectors.toMap(Block::id, b -> b));

            buildRawTextSheet(workbook, blocks);
            buildKeyValueSheet(workbook, blocks, blockMap);
            buildTablesSheet(workbook, blocks, blockMap);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            log.info("Generated Excel workbook for '{}' with {} blocks", originalFileName, blocks.size());
            return out.toByteArray();
        }
    }

    // ─── Sheet 1: Raw Text ─────────────────────────────────────────────────
    private static void buildRawTextSheet(XSSFWorkbook workbook, List<Block> blocks) {
        XSSFSheet sheet = workbook.createSheet("Raw Text");
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);

        // Header row
        Row header = sheet.createRow(0);
        createStyledCell(header, 0, "Page", headerStyle);
        createStyledCell(header, 1, "Line Text", headerStyle);
        createStyledCell(header, 2, "Confidence", headerStyle);

        int rowIdx = 1;
        for (Block block : blocks) {
            if (block.blockType() == BlockType.LINE) {
                Row row = sheet.createRow(rowIdx++);
                createStyledCell(row, 0, String.valueOf(block.page()), dataStyle);
                createStyledCell(row, 1, block.text() != null ? block.text() : "", dataStyle);
                createStyledCell(row, 2,
                        block.confidence() != null ? String.format("%.2f%%", block.confidence()) : "", dataStyle);
            }
        }

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        sheet.autoSizeColumn(2);
        sheet.setColumnWidth(1, Math.max(sheet.getColumnWidth(1), 15000));
        log.info("Raw Text sheet: {} lines", rowIdx - 1);
    }

    // ─── Sheet 2: Key-Values ───────────────────────────────────────────────
    private static void buildKeyValueSheet(XSSFWorkbook workbook, List<Block> blocks,
            Map<String, Block> blockMap) {
        XSSFSheet sheet = workbook.createSheet("Key-Values");
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);

        Row header = sheet.createRow(0);
        createStyledCell(header, 0, "Page", headerStyle);
        createStyledCell(header, 1, "Key", headerStyle);
        createStyledCell(header, 2, "Value", headerStyle);
        createStyledCell(header, 3, "Confidence", headerStyle);

        int rowIdx = 1;
        for (Block block : blocks) {
            if (block.blockType() == BlockType.KEY_VALUE_SET
                    && block.entityTypes() != null
                    && block.entityTypes().stream().anyMatch(et -> et.toString().equals("KEY"))) {

                String key = extractText(block, blockMap, RelationshipType.CHILD);
                String value = "";
                if (block.relationships() != null) {
                    for (var rel : block.relationships()) {
                        if (rel.type() == RelationshipType.VALUE) {
                            for (String valueId : rel.ids()) {
                                Block valueBlock = blockMap.get(valueId);
                                if (valueBlock != null) {
                                    value = extractText(valueBlock, blockMap, RelationshipType.CHILD);
                                }
                            }
                        }
                    }
                }

                Row row = sheet.createRow(rowIdx++);
                createStyledCell(row, 0, String.valueOf(block.page()), dataStyle);
                createStyledCell(row, 1, key, dataStyle);
                createStyledCell(row, 2, value, dataStyle);
                createStyledCell(row, 3,
                        block.confidence() != null ? String.format("%.2f%%", block.confidence()) : "", dataStyle);
            }
        }

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        sheet.autoSizeColumn(2);
        sheet.autoSizeColumn(3);
        log.info("Key-Values sheet: {} pairs", rowIdx - 1);
    }

    // ─── Sheet 3: Tables ───────────────────────────────────────────────────
    private static void buildTablesSheet(XSSFWorkbook workbook, List<Block> blocks,
            Map<String, Block> blockMap) {
        XSSFSheet sheet = workbook.createSheet("Tables");
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);

        List<Block> tables = blocks.stream()
                .filter(b -> b.blockType() == BlockType.TABLE)
                .collect(Collectors.toList());

        if (tables.isEmpty()) {
            Row row = sheet.createRow(0);
            createStyledCell(row, 0, "No tables detected in this document.", dataStyle);
            return;
        }

        int currentRow = 0;
        int tableNum = 1;

        for (Block table : tables) {
            // Table header
            Row tableHeader = sheet.createRow(currentRow++);
            XSSFCell titleCell = (XSSFCell) tableHeader.createCell(0);
            titleCell.setCellValue("Table " + tableNum++);
            titleCell.setCellStyle(headerStyle);

            // Collect cells for this table
            if (table.relationships() == null) {
                currentRow++;
                continue;
            }

            // Map rowIndex → colIndex → text
            Map<Integer, Map<Integer, String>> grid = new TreeMap<>();
            int maxRow = 0, maxCol = 0;

            for (var rel : table.relationships()) {
                if (rel.type() == RelationshipType.CHILD) {
                    for (String cellId : rel.ids()) {
                        Block cell = blockMap.get(cellId);
                        if (cell != null && cell.blockType() == BlockType.CELL) {
                            int r = cell.rowIndex() - 1;
                            int c = cell.columnIndex() - 1;
                            maxRow = Math.max(maxRow, r);
                            maxCol = Math.max(maxCol, c);
                            String text = extractText(cell, blockMap, RelationshipType.CHILD);
                            grid.computeIfAbsent(r, k -> new TreeMap<>()).put(c, text);
                        }
                    }
                }
            }

            // Write grid
            for (int r = 0; r <= maxRow; r++) {
                Row dataRow = sheet.createRow(currentRow++);
                CellStyle style = (r == 0) ? headerStyle : dataStyle;
                for (int c = 0; c <= maxCol; c++) {
                    String text = grid.getOrDefault(r, Collections.emptyMap()).getOrDefault(c, "");
                    createStyledCell(dataRow, c, text, style);
                }
            }

            // Auto-size table columns
            for (int c = 0; c <= maxCol; c++) {
                sheet.autoSizeColumn(c);
            }

            currentRow += 2; // gap between tables
        }

        log.info("Tables sheet: {} table(s) written", tableNum - 1);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static String extractText(Block block, Map<String, Block> blockMap,
            RelationshipType relType) {
        if (block == null || block.relationships() == null)
            return "";
        StringBuilder sb = new StringBuilder();
        for (var rel : block.relationships()) {
            if (rel.type() == relType) {
                for (String childId : rel.ids()) {
                    Block child = blockMap.get(childId);
                    if (child != null && child.text() != null) {
                        if (!sb.isEmpty())
                            sb.append(" ");
                        sb.append(child.text());
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    private static CellStyle createHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(new byte[] { (byte) 0x1e, (byte) 0x3a, (byte) 0x5f }, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setWrapText(false);
        return style;
    }

    private static CellStyle createDataStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setWrapText(true);
        return style;
    }

    private static void createStyledCell(Row row, int colIdx, String value, CellStyle style) {
        Cell cell = row.createCell(colIdx);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }
}
