package com.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lab.model.Grib2RecordInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计表生成器 — 以气压层为主维度的 GRIB2 数据统计报告。
 */
public class StatisticsTable {

    private static final Logger log = LoggerFactory.getLogger(StatisticsTable.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public void generate(List<Grib2RecordInfo> records, String outputDir, String sourceFile) {
        List<Grib2RecordInfo> sorted = records.stream()
                .sorted(Comparator.<Grib2RecordInfo, Boolean>comparing(
                                r -> r.getPressureLevelHPa() == null)
                        .thenComparing(Comparator.<Grib2RecordInfo, Double>comparing(
                                        r -> r.getPressureLevelHPa() != null
                                                ? r.getPressureLevelHPa() : Double.MAX_VALUE)
                                .reversed())
                        .thenComparing(Grib2RecordInfo::getVariableName))
                .collect(Collectors.toList());

        String baseName = sourceFile.substring(0, sourceFile.lastIndexOf('.'));

        printPressureLevelDashboard(sorted, sourceFile);
        printDetailTable(sorted, sourceFile);

        writeCsv(sorted, new File(outputDir, baseName + "_statistics.csv"));
        writeJson(sorted, new File(outputDir, baseName + "_statistics.json"));

        printSummary(sorted, sourceFile);
    }

    // ======================== 气压层主维度面板 ========================

    private void printPressureLevelDashboard(List<Grib2RecordInfo> records, String sourceFile) {
        var pressureRecords = records.stream()
                .filter(Grib2RecordInfo::isIsobaricLevel)
                .collect(Collectors.toList());

        var nonPressureRecords = records.stream()
                .filter(r -> !r.isIsobaricLevel())
                .collect(Collectors.toList());

        Map<Double, List<Grib2RecordInfo>> byPressure = pressureRecords.stream()
                .collect(Collectors.groupingBy(
                        Grib2RecordInfo::getPressureLevelHPa,
                        TreeMap::new,
                        Collectors.toList()));

        System.out.println();
        System.out.println(repeat("=", 90));
        System.out.println("  >>> 气压层维度统计概览 -- " + sourceFile + " <<<");
        System.out.println(repeat("=", 90));

        System.out.printf("  气压层总数:       %d 层%n", byPressure.size());
        System.out.printf("  等压面记录数:     %d 条%n", pressureRecords.size());
        System.out.printf("  非等压面记录数:   %d 条%n", nonPressureRecords.size());
        System.out.printf("  变量种类:         %d 种%n",
                pressureRecords.stream().map(Grib2RecordInfo::getVariableAbbrev).distinct().count());
        System.out.printf("  预报时效:         %s%n",
                records.stream()
                        .map(r -> r.getForecastHours() + "h")
                        .distinct().sorted()
                        .collect(Collectors.joining(", ")));
        System.out.println();

        // 气压层列表
        System.out.println("  --- 气压层列表 (hPa, 高空 -> 地面) ---");
        int i = 1;
        for (var entry : byPressure.entrySet()) {
            Double hPa = entry.getKey();
            String varList = entry.getValue().stream()
                    .map(Grib2RecordInfo::getVariableAbbrev)
                    .distinct()
                    .collect(Collectors.joining(", "));
            System.out.printf("  %2d. %7.0f hPa  ->  %s%n", i++, hPa, varList);
        }
        System.out.println();

        // 逐层统计
        System.out.println("  --- 逐层数据统计 ---");
        String statFmt = "  %-8s %-8s %-10s %10s %10s %10s %8s %-12s%n";
        System.out.printf(statFmt, "气压(hPa)", "变量数", "NX x NY", "min", "max", "mean", "填充率", "unit");
        System.out.println("  " + repeat("-", 85));

        for (var entry : byPressure.entrySet()) {
            Double hPa = entry.getKey();
            List<Grib2RecordInfo> vars = entry.getValue();
            long varCount = vars.stream().map(Grib2RecordInfo::getVariableAbbrev).distinct().count();

            Double layerMin = vars.stream()
                    .map(Grib2RecordInfo::getDataMin).filter(Objects::nonNull)
                    .min(Double::compareTo).orElse(null);
            Double layerMax = vars.stream()
                    .map(Grib2RecordInfo::getDataMax).filter(Objects::nonNull)
                    .max(Double::compareTo).orElse(null);

            DoubleSummaryStatistics stats = vars.stream()
                    .filter(v -> v.getDataMean() != null)
                    .mapToDouble(Grib2RecordInfo::getDataMean)
                    .summaryStatistics();

            Grib2RecordInfo first = vars.get(0);
            String units = vars.stream()
                    .map(Grib2RecordInfo::getUnit).distinct()
                    .collect(Collectors.joining(", "));

            // 汇总该层所有变量的有效网格
            long totalCells = first.getNx() * (long) first.getNy();
            long validCells = vars.stream()
                    .mapToLong(Grib2RecordInfo::getDataValidCount)
                    .sum();
            String fillStr = totalCells > 0
                    ? String.format("%.1f%%", validCells * 100.0 / totalCells) : "-";

            System.out.printf("  %-8.0f %-8d %-10s %10s %10s %10s %8s %-12s%n",
                    hPa, varCount,
                    first.getNx() + "x" + first.getNy(),
                    layerMin != null ? String.format("%.4f", layerMin) : "-",
                    layerMax != null ? String.format("%.4f", layerMax) : "-",
                    stats.getCount() > 0 ? String.format("%.4f", stats.getAverage()) : "-",
                    fillStr,
                    truncate(units, 12));
        }
        System.out.println("  " + repeat("-", 85));
        System.out.println();

        // 变量 x 气压层 矩阵
        printVariablePressureMatrix(byPressure);

        // 非等压面层次
        if (!nonPressureRecords.isEmpty()) {
            System.out.println("  --- 非等压面层次 ---");
            var byType = nonPressureRecords.stream()
                    .collect(Collectors.groupingBy(
                            Grib2RecordInfo::getLevelType, LinkedHashMap::new, Collectors.toList()));
            for (var entry : byType.entrySet()) {
                String vars = entry.getValue().stream()
                        .map(Grib2RecordInfo::getVariableAbbrev)
                        .distinct().collect(Collectors.joining(", "));
                System.out.printf("    %s: %s%n", entry.getKey(), vars);
            }
            System.out.println();
        }
    }

    private void printVariablePressureMatrix(Map<Double, List<Grib2RecordInfo>> byPressure) {
        if (byPressure.isEmpty()) return;

        List<String> allVars = byPressure.values().stream()
                .flatMap(List::stream)
                .map(Grib2RecordInfo::getVariableAbbrev)
                .distinct().sorted()
                .collect(Collectors.toList());

        List<Double> allLevels = new ArrayList<>(byPressure.keySet());

        System.out.println("  --- 变量 x 气压层 覆盖矩阵 (X=存在) ---");
        System.out.println();

        // 表头
        System.out.printf("  %-22s", "变量 / 气压(hPa)");
        for (Double level : allLevels) {
            System.out.printf(" %7.0f", level);
        }
        System.out.println();

        for (String var : allVars) {
            System.out.printf("  %-22s", truncate(var, 22));
            for (Double level : allLevels) {
                boolean exists = byPressure.get(level).stream()
                        .anyMatch(r -> var.equals(r.getVariableAbbrev()));
                System.out.printf(" %7s", exists ? "X" : ".");
            }
            System.out.println();
        }
        System.out.println();
    }

    // ======================== 记录明细表 ========================

    private void printDetailTable(List<Grib2RecordInfo> records, String sourceFile) {
        String divider = "+------+------------------------+----------+------------------+------------+-----------+------------+---------------+-----------+-----------+-----------+";
        String headerFmt = "| %-4s | %-22s | %-8s | %-16s | %-10s | %-9s | %-10s | %-13s | %-9s | %-9s | %-9s |";

        System.out.println(repeat("=", divider.length()));
        System.out.println("  记录明细 -- " + sourceFile);
        System.out.println(repeat("=", divider.length()));
        System.out.println(divider);
        System.out.println(String.format(headerFmt,
                "序号", "变量名", "缩写", "层次类型", "气压(hPa)", "预报时效", "参考时间",
                "投影", "NX", "NY", "单位"));
        System.out.println(divider);

        for (Grib2RecordInfo r : records) {
            System.out.println(String.format(
                    "| %4d | %-22s | %-8s | %-16s | %10s | %9s | %10s | %-13s | %7d | %7d | %-9s |",
                    r.getRecordIndex(),
                    truncate(r.getVariableName(), 22),
                    truncate(r.getVariableAbbrev(), 8),
                    truncate(r.getLevelType(), 16),
                    r.getPressureLevelHPa() != null
                            ? String.format("%.0f", r.getPressureLevelHPa()) : "-",
                    r.getForecastHours() > 0
                            ? String.format("+%dh", r.getForecastHours())
                            : r.getForecastHours() == 0 ? "analysis" : "N/A",
                    truncate(r.getReferenceTime(), 10),
                    truncate(r.getGridProjection(), 13),
                    r.getNx(),
                    r.getNy(),
                    truncate(r.getUnit(), 9)));
        }

        System.out.println(divider);
        System.out.println("  共 " + records.size() + " 条记录");
        System.out.println();
    }

    // ======================== CSV ========================

    private void writeCsv(List<Grib2RecordInfo> records, File csvFile) {
        try {
            try (PrintWriter pw = new PrintWriter(
                    new FileWriter(csvFile, StandardCharsets.UTF_8))) {
                pw.print('﻿'); // BOM
                pw.println("序号,变量名,缩写,层次类型,气压(hPa),层次值1,层次值2,"
                        + "预报时效(h),参考时间,投影,NX,NY,总网格数,有效网格数,填充率(%),最小值,最大值,平均值,单位,描述");

                for (Grib2RecordInfo r : records) {
                    String fillPct = r.getDataFillRatio() != null
                            ? String.format("%.1f", r.getDataFillRatio()) : "";
                    pw.printf("%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%s,%s,%s,%s,%s,\"%s\"%n",
                            r.getRecordIndex(),
                            csvEscape(r.getVariableName()),
                            csvEscape(r.getVariableAbbrev()),
                            csvEscape(r.getLevelType()),
                            r.getPressureLevelHPa() != null
                                    ? String.format("%.1f", r.getPressureLevelHPa()) : "",
                            r.getLevelValue1() != null ? r.getLevelValue1().toString() : "",
                            r.getLevelValue2() != null ? r.getLevelValue2().toString() : "",
                            r.getForecastHours(),
                            csvEscape(r.getReferenceTime()),
                            csvEscape(r.getGridProjection()),
                            r.getNx(), r.getNy(),
                            r.getDataTotalCount(),
                            r.getDataValidCount(),
                            fillPct,
                            r.getDataMin() != null
                                    ? String.format("%.4f", r.getDataMin()) : "",
                            r.getDataMax() != null
                                    ? String.format("%.4f", r.getDataMax()) : "",
                            r.getDataMean() != null
                                    ? String.format("%.4f", r.getDataMean()) : "",
                            csvEscape(r.getUnit()),
                            csvEscape(r.getDescription()));
                }
            }
            log.info("CSV 统计表已写入: {}", csvFile.getAbsolutePath());
        } catch (IOException e) {
            // 文件被锁时，尝试写带时间戳的副本
            String altName = csvFile.getAbsolutePath().replace(".csv",
                    "_" + System.currentTimeMillis() % 100000 + ".csv");
            File altFile = new File(altName);
            try (PrintWriter pw = new PrintWriter(
                    new FileWriter(altFile, StandardCharsets.UTF_8))) {
                pw.print('﻿');
                pw.println("序号,变量名,缩写,层次类型,气压(hPa),层次值1,层次值2,"
                        + "预报时效(h),参考时间,投影,NX,NY,总网格数,有效网格数,填充率(%),最小值,最大值,平均值,单位,描述");
                for (Grib2RecordInfo r : records) {
                    String fillPct = r.getDataFillRatio() != null
                            ? String.format("%.1f", r.getDataFillRatio()) : "";
                    pw.printf("%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%s,%s,%s,%s,%s,\"%s\"%n",
                            r.getRecordIndex(),
                            csvEscape(r.getVariableName()),
                            csvEscape(r.getVariableAbbrev()),
                            csvEscape(r.getLevelType()),
                            r.getPressureLevelHPa() != null
                                    ? String.format("%.1f", r.getPressureLevelHPa()) : "",
                            r.getLevelValue1() != null ? r.getLevelValue1().toString() : "",
                            r.getLevelValue2() != null ? r.getLevelValue2().toString() : "",
                            r.getForecastHours(),
                            csvEscape(r.getReferenceTime()),
                            csvEscape(r.getGridProjection()),
                            r.getNx(), r.getNy(),
                            r.getDataTotalCount(),
                            r.getDataValidCount(),
                            fillPct,
                            r.getDataMin() != null
                                    ? String.format("%.4f", r.getDataMin()) : "",
                            r.getDataMax() != null
                                    ? String.format("%.4f", r.getDataMax()) : "",
                            r.getDataMean() != null
                                    ? String.format("%.4f", r.getDataMean()) : "",
                            csvEscape(r.getUnit()),
                            csvEscape(r.getDescription()));
                }
            } catch (IOException e2) {
                log.error("写入 CSV 失败: {}", e2.getMessage());
            }
            log.warn("原文件被占用，CSV 已写入备用文件: {}", altName);
        }
    }

    // ======================== JSON ========================

    private void writeJson(List<Grib2RecordInfo> records, File jsonFile) {
        try {
            MAPPER.writeValue(jsonFile, records);
            log.info("JSON 统计表已写入: {}", jsonFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("写入 JSON 失败: {}", e.getMessage());
        }
    }

    // ======================== 日志汇总 ========================

    private void printSummary(List<Grib2RecordInfo> records, String sourceFile) {
        long analysisCount = records.stream().filter(Grib2RecordInfo::isAnalysis).count();
        long forecastCount = records.size() - analysisCount;

        var pressureLevels = records.stream()
                .filter(Grib2RecordInfo::isIsobaricLevel)
                .map(Grib2RecordInfo::getPressureLevelHPa)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());

        log.info("========================================");
        log.info("文件: {}", sourceFile);
        log.info("  记录数: {} | 分析场: {} | 预报场: {}",
                records.size(), analysisCount, forecastCount);
        log.info("  气压层 ({} 层): {}",
                pressureLevels.size(),
                pressureLevels.stream().map(p -> String.format("%.0f", p))
                        .collect(Collectors.joining(", ")));

        var levelTypes = records.stream()
                .map(Grib2RecordInfo::getLevelType)
                .distinct().sorted().collect(Collectors.toList());
        log.info("  层次类型: {}", levelTypes);

        var variables = records.stream()
                .map(Grib2RecordInfo::getVariableAbbrev)
                .distinct().sorted().collect(Collectors.toList());
        log.info("  变量: {}", variables);

        var projections = records.stream()
                .map(Grib2RecordInfo::getGridProjection)
                .distinct().sorted().collect(Collectors.toList());
        log.info("  投影: {}", projections);
        log.info("========================================");
    }

    // ======================== 工具 ========================

    private String truncate(String s, int maxLen) {
        if (s == null) return "-";
        return s.length() > maxLen ? s.substring(0, maxLen - 1) + "." : s;
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }
}
