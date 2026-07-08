package com.lab.water.classify;

import com.lab.water.model.WaterBody;
import com.lab.water.model.WaterBodyType;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

/**
 * 水体分类器 — 计算形态指标并判定类型与层级。
 *
 * <p>核心逻辑：</p>
 * <ul>
 *   <li>从 Polygon/MultiPolygon 计算周长、面积、椭圆度、狭长度</li>
 *   <li>根据阈值判定 LAKE / RIVER / OCEAN</li>
 *   <li>按面积划分层级</li>
 * </ul>
 */
public class WaterBodyClassifier {

    private final ClassificationConfig config;

    public WaterBodyClassifier(ClassificationConfig config) {
        this.config = config;
    }

    public WaterBodyClassifier() {
        this(ClassificationConfig.defaults());
    }

    /**
     * 对单个水体要素执行完整分类，填充形态指标与分类结果。
     */
    public WaterBody classify(WaterBody waterBody) {
        Geometry geom = waterBody.getGeometry();
        if (geom == null || geom.isEmpty()) {
            waterBody.setWaterType(WaterBodyType.UNKNOWN);
            return waterBody;
        }

        // 1. 计算面积
        double areaM2 = geom.getArea();
        double areaKm2 = areaM2 / 1_000_000;
        waterBody.setArea(areaM2);
        waterBody.setAreaSqKm(areaKm2);

        // 2. 计算周长
        double perimeter = geom.getLength();
        waterBody.setPerimeter(perimeter);

        // 3. 计算最小外接矩形（MBR），提取长轴/短轴
        Geometry mbr = MinimumDiameter.getMinimumRectangle(geom);
        if (mbr instanceof Polygon p && p.getNumPoints() >= 3) {
            Coordinate[] coords = p.getCoordinates();
            // MBR 有 4 条边（coords 有 5 个点，首尾闭合）
            double edge01 = coords[0].distance(coords[1]);
            double edge12 = coords[1].distance(coords[2]);

            double majorAxis = Math.max(edge01, edge12);
            double minorAxis = Math.min(edge01, edge12);

            waterBody.setMajorAxis(majorAxis);
            waterBody.setMinorAxis(minorAxis);

            // 4. 椭圆度 = 外接椭圆面积 / 实际面积
            //    外接椭圆面积 = π * (a/2) * (b/2) = π * a * b / 4
            //    椭圆度 = (π * a * b / 4) / area → 越接近 1 越圆
            double ellipticity = areaM2 > 0
                    ? Math.PI * majorAxis * minorAxis / (4.0 * areaM2)
                    : 0;
            waterBody.setEllipticity(ellipticity);

            // 5. 狭长度 = 长轴 / 短轴
            double slenderness = minorAxis > 0 ? majorAxis / minorAxis : Double.MAX_VALUE;
            waterBody.setSlenderness(slenderness);
        }

        // 6. 判定类型
        WaterBodyType type = classifyType(
                waterBody.getAreaSqKm(),
                waterBody.getEllipticity(),
                waterBody.getSlenderness());
        waterBody.setWaterType(type);

        // 7. 划分层级
        int level = config.resolveLevel(waterBody.getAreaSqKm());
        waterBody.setHierarchyLevel(level);
        waterBody.setHierarchyLabel(ClassificationConfig.levelLabel(level));

        return waterBody;
    }

    /**
     * 根据形态指标判定水体类型：
     * <ul>
     *   <li>面积 ≥ 大洋阈值 → OCEAN</li>
     *   <li>狭长度高（狭长）→ RIVER</li>
     *   <li>其余 → LAKE</li>
     * </ul>
     */
    private WaterBodyType classifyType(double areaKm2, double ellipticity, double slenderness) {
        if (areaKm2 >= config.getOceanMinAreaSqKm()) {
            return WaterBodyType.OCEAN;
        }
        // 狭长水体 → 河流（狭长度是主要判据）
        if (slenderness >= config.getRiverMinSlenderness()) {
            return WaterBodyType.RIVER;
        }
        return WaterBodyType.LAKE;
    }
}
