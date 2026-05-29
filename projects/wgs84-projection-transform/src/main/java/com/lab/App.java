package com.lab;

import lombok.extern.slf4j.Slf4j;
import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * GeoJSON 坐标系批量转换实验
 * 目标: 将 data/ 下的 4326 GeoJSON 转换为 3411, 3412, 3857
 */
@Slf4j
public class App {

    public static void main(String[] args) {
        // 将 Java Util Logging (JUL) 桥接到 SLF4J
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();

        log.info("开始 GeoJSON 批量坐标转换实验");

        try {
            System.setProperty("org.geotools.referencing.forceXY", "true");
            CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:4326");
            String[] targetCodes = {"EPSG:3411", "EPSG:3412", "EPSG:3857"};

            File dataDir = new File("data");
            File[] inputFiles = dataDir.listFiles((dir, name) -> name.endsWith(".json") || name.endsWith(".geojson"));

            if (inputFiles == null || inputFiles.length == 0) {
                log.warn("data 文件夹下未找到 GeoJSON 文件");
                return;
            }

            Path outputPath = Paths.get("output");
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            }

            for (File inputFile : inputFiles) {
                log.info("正在处理文件: {}", inputFile.getName());
                for (String targetCode : targetCodes) {
                    GeoJSONTransformer.transform(inputFile, sourceCRS, targetCode);
                }
            }

        } catch (Exception e) {
            log.error("实验过程中发生错误", e);
        }

        log.info("实验结束");
        System.exit(0);
    }
}
