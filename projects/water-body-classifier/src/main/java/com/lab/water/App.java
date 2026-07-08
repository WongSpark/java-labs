package com.lab.water;

import com.lab.water.classify.ClassificationConfig;
import com.lab.water.classify.WaterBodyClassifier;
import com.lab.water.io.ShapeFileReader;
import com.lab.water.io.ShapeFileWriter;
import com.lab.water.model.WaterBody;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.File;
import java.util.List;

/**
 * 世界水体分类与分级处理入口。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>读取输入 SHP（世界水系数据）</li>
 *   <li>计算每个水体的周长、面积、椭圆度、狭长度</li>
 *   <li>判定水体类型（湖泊 / 河流 / 大洋）</li>
 *   <li>按面积划分层级</li>
 *   <li>按层级输出独立的 SHP 文件</li>
 * </ol>
 */
@Slf4j
public class App {

    // ============ 可配置参数 ============

    /** 输入 SHP 文件路径 */
    private static final String INPUT_SHP = "data/input/WorldWater.shp";

    /** 输出目录 */
    private static final String OUTPUT_DIR = "output/";

    /** 区域前缀（用于输出文件名） */
    private static final String REGION_PREFIX = "World";

    // ===================================

    public static void main(String[] args) {
        // 初始化日志桥接（jul → slf4j）
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        log.info("===== 世界水体分类处理 开始 =====");

        try {
            // 1. 配置
            ClassificationConfig config = ClassificationConfig.defaults();
            log.info("分类配置: {}", config);

            // 2. 读取 SHP
            ShapeFileReader reader = new ShapeFileReader();
            File inputFile = new File(INPUT_SHP);
            if (!inputFile.exists()) {
                log.warn("输入文件不存在: {}，使用模拟数据", inputFile.getAbsolutePath());
                return;
            }
            List<WaterBody> allWaterBodies = reader.read(inputFile);

            // 3. 分类
            WaterBodyClassifier classifier = new WaterBodyClassifier(config);
            List<WaterBody> classified = allWaterBodies.stream()
                    .map(classifier::classify)
                    .toList();

            // 4. 统计
            long lakeCount = classified.stream().filter(w -> w.getWaterType().name().equals("LAKE")).count();
            long riverCount = classified.stream().filter(w -> w.getWaterType().name().equals("RIVER")).count();
            long oceanCount = classified.stream().filter(w -> w.getWaterType().name().equals("OCEAN")).count();
            log.info("分类统计 — 湖泊: {}, 河流: {}, 大洋: {}", lakeCount, riverCount, oceanCount);

            // 5. 按层级输出 SHP
            ShapeFileWriter writer = new ShapeFileWriter();
            File outputDir = new File(OUTPUT_DIR);
            outputDir.mkdirs();

            for (int level = 1; level <= 4; level++) {
                int lvl = level;
                List<WaterBody> levelBodies = classified.stream()
                        .filter(w -> w.getHierarchyLevel() == lvl)
                        .toList();
                if (levelBodies.isEmpty()) {
                    log.info("Level-{} 无要素，跳过", level);
                    continue;
                }
                String label = ClassificationConfig.levelLabel(level);
                writer.write(outputDir, REGION_PREFIX, label, levelBodies);
            }

            log.info("===== 世界水体分类处理 完成 =====");

        } catch (Exception e) {
            log.error("处理过程中出错", e);
            System.exit(1);
        }
    }
}
