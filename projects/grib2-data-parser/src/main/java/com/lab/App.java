package com.lab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * GRIB2 数据解析与统计表生成工具 — 入口。
 *
 * <p>扫描 data/ 目录下的所有 .grib2 / .grb2 / .grb 文件，
 * 对其中的每个气象变量提取元数据、气压层信息及统计值（min/max/mean），
 * 并以控制台表格、CSV、JSON 三种格式输出统计报告。
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn clean compile exec:java
 * mvn exec:java "-Dexec.args=/path/to/data"
 * </pre>
 */
public class App {

    static {
        // 将 java.util.logging 桥接到 SLF4J，统一日志输出
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    private static final Logger log = LoggerFactory.getLogger(App.class);

    /** GRIB2 文件扩展名 */
    private static final List<String> GRIB_EXTENSIONS = Arrays.asList(
            ".grib2", ".grb2", ".grb", ".grib", ".gb2", ".g2"
    );

    public static void main(String[] args) {
        // 确定数据目录：系统属性 > 命令行参数 > 默认值
        String dataDir = System.getProperty("app.datadir",
                args.length > 0 ? args[0] : "data");
        String outputDir = System.getProperty("app.outdir", "output");

        File dataFolder = new File(dataDir);
        if (!dataFolder.exists() || !dataFolder.isDirectory()) {
            log.error("数据目录不存在: {}", dataFolder.getAbsolutePath());
            log.info("可用系统属性覆盖: -Dapp.datadir=/path/to/data");
            System.exit(1);
        }

        // 扫描 GRIB2 文件
        File[] gribFiles = dataFolder.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return GRIB_EXTENSIONS.stream().anyMatch(lower::endsWith);
        });

        if (gribFiles == null || gribFiles.length == 0) {
            log.warn("在 {} 目录下未找到 GRIB2 文件 (扩展名: {})",
                    dataFolder.getAbsolutePath(), GRIB_EXTENSIONS);
            log.info("请将 .grib2 / .grb2 / .grb 文件放入 data/ 目录后重新运行");
            return;
        }

        log.info("找到 {} 个 GRIB2 文件", gribFiles.length);

        // 确保输出目录存在
        File outDir = new File(outputDir);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        // 逐个处理
        Grib2Reader reader = new Grib2Reader();
        StatisticsTable table = new StatisticsTable();

        for (File gribFile : gribFiles) {
            try {
                log.info("----------------------------------------");
                log.info("处理文件: {}", gribFile.getName());

                List<com.lab.model.Grib2RecordInfo> records =
                        reader.read(gribFile.getAbsolutePath());

                if (records.isEmpty()) {
                    log.warn("未从文件中提取到记录");
                    continue;
                }

                table.generate(records, outputDir, gribFile.getName());

            } catch (IOException e) {
                log.error("处理文件 {} 失败: {}", gribFile.getName(), e.getMessage(), e);
            }
        }

        log.info("全部处理完成。输出目录: {}", outDir.getAbsolutePath());
    }
}
