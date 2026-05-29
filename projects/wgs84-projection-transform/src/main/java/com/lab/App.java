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

            Path projectDir = resolveProjectDir();
            File dataDir = projectDir.resolve("data").toFile();

            File[] inputFiles = dataDir.listFiles((dir, name) -> name.endsWith(".json") || name.endsWith(".geojson"));

            if (inputFiles == null || inputFiles.length == 0) {
                log.warn("data 文件夹下未找到 GeoJSON 文件，已搜索路径: {}", dataDir.getAbsolutePath());
                return;
            }

            Path outputPath = projectDir.resolve("output");
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            }

            for (File inputFile : inputFiles) {
                log.info("正在处理文件: {}", inputFile.getName());
                for (String targetCode : targetCodes) {
                    GeoJSONTransformer.transform(inputFile, sourceCRS, targetCode, outputPath.toFile());
                }
            }

        } catch (Exception e) {
            log.error("实验过程中发生错误", e);
        }

        log.info("实验结束");
    }

    /**
     * 自动定位项目根目录：先尝试 user.dir（mvn 终端运行），
     * 若 data/ 不存在则根据类文件路径向上查找 pom.xml（兼容 VSCode Debug 等任意工作目录）。
     */
    private static Path resolveProjectDir() {
        Path userDir = Paths.get(System.getProperty("user.dir"));
        if (userDir.resolve("data").toFile().exists()) {
            return userDir;
        }
        // 根据类文件路径定位：target/classes/ → 项目根目录
        // 使用 toURI() 避免 Windows 下 getPath() 返回 "/D:/..." 导致解析失败
        try {
            Path classPath = Paths.get(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path current = classPath.normalize();
            while (current != null) {
                if (current.resolve("pom.xml").toFile().exists()) {
                    return current;
                }
                current = current.getParent();
            }
        } catch (Exception ignored) {
        }
        log.warn("未找到 data/ 目录，尝试使用当前工作目录: {}", userDir.toAbsolutePath());
        return userDir;
    }
}
