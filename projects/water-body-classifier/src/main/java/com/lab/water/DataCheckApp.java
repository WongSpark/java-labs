package com.lab.water;

import com.lab.water.io.ShapeFileHelper;
import org.geotools.api.data.Query;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.File;

/**
 * 数据检查 — 对比 RoughlyWater_All 与 WorldWater_20sqkm
 *
 * 直接利用属性字段 area/length 做面积周长分析。
 */
public class DataCheckApp {

    private static final Logger log = LoggerFactory.getLogger(DataCheckApp.class);

    static final String WORLD_WATER = "D:/GIS/Data/AirChinaProject/GlobalVectorData/WorldWater_20sqkm.shp";
    static final String ROUGHLY_WATER = "D:/GIS/Data/AirChinaProject/GlobalVectorData/全球河湖矢量数据/RoughlyWater_All.shp";

    public static void main(String[] args) throws Exception {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        // schema: 看字段结构, area: 面积周长分布, contain: 空间包含检查
        String mode = args.length > 0 ? args[0] : "all";

        switch (mode) {
            case "schema" -> {
                inspectSchema(new File(WORLD_WATER), "WorldWater_20sqkm");
                inspectSchema(new File(ROUGHLY_WATER), "RoughlyWater_All");
            }
            case "area" -> analyzeRoughlyWater(new File(ROUGHLY_WATER));
            case "contain" -> compareContainment(new File(WORLD_WATER), new File(ROUGHLY_WATER));
            default -> {
                inspectSchema(new File(WORLD_WATER), "WorldWater_20sqkm");
                inspectSchema(new File(ROUGHLY_WATER), "RoughlyWater_All");
                log.info("---");
                analyzeRoughlyWater(new File(ROUGHLY_WATER));
            }
        }
    }

    // ========== 基本信息 ==========

    static void inspectSchema(File shp, String label) throws Exception {
        ShapeFileHelper.readDataStore(shp, store -> {
            try {
                String typeName = store.getTypeNames()[0];
                ContentFeatureSource src = (ContentFeatureSource) store.getFeatureSource(typeName);
                int count = src.getCount(Query.ALL);
                log.info("【{}】要素数: {}", label, count);
                log.info("【{}】属性字段:", label);
                src.getSchema().getDescriptors().forEach(desc ->
                        log.info("   - {} ({})", desc.getName(), desc.getType().getBinding().getSimpleName()));

                // 前 3 条样例
                int n = 0;
                try (SimpleFeatureIterator it = src.getFeatures().features()) {
                    while (it.hasNext() && n < 3) {
                        SimpleFeature f = it.next();
                        log.info("【{}】样例 {}: ObjectID={}, area={}, length={}",
                                label, n,
                                f.getAttribute("OBJECTID"),
                                f.getAttribute("area"),
                                f.getAttribute("length"));
                        n++;
                    }
                }
            } catch (Exception e) {
                log.error("检查 {} 失败", label, e);
            }
            return null;
        });
    }

    // ========== 面积/周长分析 ==========

    static void analyzeRoughlyWater(File shp) throws Exception {
        ShapeFileHelper.readDataStore(shp, store -> {
            try {
                String typeName = store.getTypeNames()[0];
                ContentFeatureSource src = (ContentFeatureSource) store.getFeatureSource(typeName);

                long total = 0;
                double minArea = Double.MAX_VALUE, maxArea = 0, sumArea = 0;
                double minLen = Double.MAX_VALUE, maxLen = 0;
                // 面积分段 (km²)
                long[] buckets = new long[10];
                String[] labels = {"<1", "1~10", "10~100", "100~1k", "1k~10k",
                        "10k~100k", "100k~500k", "500k~1M", "1M~10M", ">=10M"};
                // 阈值检查
                long below20sqkm = 0; // area < 20 km²
                long below1sqkm = 0;  // area < 1 km²
                long lenBelow100 = 0; // length < 100 m
                long lenBelow1000 = 0; // length < 1000 m

                try (SimpleFeatureIterator it = src.getFeatures().features()) {
                    while (it.hasNext()) {
                        SimpleFeature f = it.next();
                        total++;

                        Number areaVal = (Number) f.getAttribute("area");
                        Number lenVal = (Number) f.getAttribute("length");
                        if (areaVal == null || lenVal == null) continue;

                        double area = areaVal.doubleValue();
                        double len = lenVal.doubleValue();

                        if (area < minArea) minArea = area;
                        if (area > maxArea) maxArea = area;
                        sumArea += area;
                        if (len < minLen) minLen = len;
                        if (len > maxLen) maxLen = len;

                        // 判断 area 的单位并分段
                        // 如果 area 是 m²，20km² = 20,000,000 m²
                        // 如果 area 是 km²，20km² = 20
                        double areaKm2;
                        if (maxArea > 1_000_000) {
                            areaKm2 = area / 1_000_000; // 单位是 m²
                        } else if (maxArea > 1_000) {
                            areaKm2 = area; // 单位已经是 km²
                        } else {
                            areaKm2 = area; // 无法判断，当 km² 用
                        }

                        if (areaKm2 < 1) buckets[0]++;
                        else if (areaKm2 < 10) buckets[1]++;
                        else if (areaKm2 < 100) buckets[2]++;
                        else if (areaKm2 < 1000) buckets[3]++;
                        else if (areaKm2 < 10000) buckets[4]++;
                        else if (areaKm2 < 100000) buckets[5]++;
                        else if (areaKm2 < 500000) buckets[6]++;
                        else if (areaKm2 < 1_000_000) buckets[7]++;
                        else buckets[8]++;

                        // 阈值检查 — 直接用 area 原始值判断, 看数值特征
                        if (area < 20) below20sqkm++;
                        if (area < 1) below1sqkm++;
                        if (len < 100) lenBelow100++;
                        if (len < 1000) lenBelow1000++;
                    }
                }

                String areaUnit = maxArea > 1_000_000 ? "m²" : "km²";
                log.info("RoughlyWater_All 分析 ({} 个要素):", total);
                log.info("  area 范围: {} ~ {}  (单位: {})",
                        fmt(minArea), fmt(maxArea), areaUnit);
                log.info("  length 范围: {} ~ {}  (单位: m, 推测)",
                        fmt(minLen), fmt(maxLen));
                log.info("  总面积: {} {}", fmt(sumArea), areaUnit);

                log.info("  面积分段:");
                for (int i = 0; i < 9; i++) {
                    if (buckets[i] > 0) {
                        log.info("    {} km²: {} 个", labels[i], buckets[i]);
                    }
                }

                log.info("  阈值检查:");
                if (maxArea > 1_000_000) {
                    // 单位是 m²
                    log.info("    area < 20 km²  ({} m²): {} 个, 占比 {}%", fmt(20_000_000),
                            below20sqkm, pct(below20sqkm, total));
                    log.info("    area < 1 km²  ({} m²): {} 个, 占比 {}%", fmt(1_000_000),
                            below1sqkm, pct(below1sqkm, total));
                } else {
                    log.info("    area < 20 km²: {} 个, 占比 {}%",
                            below20sqkm, pct(below20sqkm, total));
                    log.info("    area < 1 km²: {} 个, 占比 {}%",
                            below1sqkm, pct(below1sqkm, total));
                }
                log.info("    length < 100 m: {} 个, 占比 {}%",
                        lenBelow100, pct(lenBelow100, total));
                log.info("    length < 1000 m: {} 个, 占比 {}%",
                        lenBelow1000, pct(lenBelow1000, total));

            } catch (Exception e) {
                log.error("分析 RoughlyWater 失败", e);
            }
            return null;
        });
    }

    // ========== 空间包含对比（使用 STRtree 空间索引，全量遍历） ==========

    static void compareContainment(File worldFile, File roughFile) throws Exception {
        log.info("====== 空间包含对比 (构建 WorldWater 索引) ======");

        // 1. 加载全部 WorldWater 几何 + 构建空间索引
        java.util.List<Geometry> worldGeoms = new java.util.ArrayList<>();
        ShapeFileHelper.readDataStore(worldFile, store -> {
            try {
                String tn = store.getTypeNames()[0];
                ContentFeatureSource src = (ContentFeatureSource) store.getFeatureSource(tn);
                try (SimpleFeatureIterator it = src.getFeatures().features()) {
                    while (it.hasNext()) {
                        Geometry g = (Geometry) it.next().getDefaultGeometry();
                        if (g != null && !g.isEmpty()) worldGeoms.add(g);
                    }
                }
            } catch (Exception e) { log.error("加载 WorldWater 失败", e); }
            return null;
        });

        org.locationtech.jts.index.strtree.STRtree index = new org.locationtech.jts.index.strtree.STRtree();
        for (int i = 0; i < worldGeoms.size(); i++) {
            index.insert(worldGeoms.get(i).getEnvelopeInternal(), worldGeoms.get(i));
        }
        index.build();
        log.info("  WorldWater 已加载 {} 个几何, 空间索引就绪", worldGeoms.size());

        // 2. 遍历 RoughlyWater, 采样检查包含关系
        ShapeFileHelper.readDataStore(roughFile, roughStore -> {
            try {
                String tn = roughStore.getTypeNames()[0];
                ContentFeatureSource src = (ContentFeatureSource) roughStore.getFeatureSource(tn);
                int totalCount = src.getCount(Query.ALL);

                long contained = 0, partial = 0, notFound = 0, checked = 0;
                int sampleSize = Math.min(200, totalCount);
                int everyN = Math.max(1, totalCount / sampleSize);

                int idx = 0;
                try (SimpleFeatureIterator it = src.getFeatures().features()) {
                    while (it.hasNext() && checked < sampleSize) {
                        SimpleFeature f = it.next();
                        Geometry g = (Geometry) f.getDefaultGeometry();
                        if (g == null || g.isEmpty()) continue;
                        idx++;
                        if (idx % everyN != 0) continue;
                        checked++;

                        // 空间索引查询候选
                        java.util.List<?> candidates = index.query(g.getEnvelopeInternal());

                        boolean full = false, any = false;
                        for (Object obj : candidates) {
                            Geometry c = (Geometry) obj;
                            if (c.contains(g)) { full = true; break; }
                            else if (c.intersects(g)) { any = true; }
                        }

                        if (full) contained++;
                        else if (any) partial++;
                        else notFound++;
                    }
                }

                log.info("采样 {} 条 / 共 {} 条:", checked, totalCount);
                log.info("  完全包含在 WorldWater 内: {} ({})", contained, pct(contained, checked));
                log.info("  部分重叠: {} ({})", partial, pct(partial, checked));
                log.info("  完全不在 WorldWater 中: {} ({})", notFound, pct(notFound, checked));

            } catch (Exception e) { log.error("对比失败", e); }
            return null;
        });
    }

    // ========== 工具 ==========

    static String fmt(double v) { return String.format("%.4f", v); }

    static String pct(long n, long total) {
        return total > 0 ? String.format("%.1f%%", 100.0 * n / total) : "0%";
    }
}
