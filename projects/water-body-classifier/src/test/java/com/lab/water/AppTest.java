package com.lab.water;

import com.lab.water.classify.ClassificationConfig;
import com.lab.water.classify.WaterBodyClassifier;
import com.lab.water.model.WaterBody;
import com.lab.water.model.WaterBodyType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 水体分类器单元测试
 */
public class AppTest {

    private static final GeometryFactory GF = new GeometryFactory();
    private static WaterBodyClassifier classifier;

    @BeforeAll
    static void setup() {
        classifier = new WaterBodyClassifier(ClassificationConfig.defaults());
    }

    @Test
    void testLakeClassification() {
        // 创建一个近似圆形的多边形 → 应分类为湖泊
        MultiPolygon mp = createCirclePolygon(1000); // 半径 1000m
        WaterBody wb = WaterBody.builder().fid(1).geometry(mp).build();
        WaterBody result = classifier.classify(wb);

        assertEquals(WaterBodyType.LAKE, result.getWaterType());
        assertTrue(result.getArea() > 0);
        assertTrue(result.getPerimeter() > 0);
        assertTrue(result.getEllipticity() > 0.5); // 圆形椭圆度应接近 1
    }

    @Test
    void testRiverClassification() {
        // 创建一个非常狭长的多边形 → 应分类为河流
        MultiPolygon mp = createNarrowPolygon();
        WaterBody wb = WaterBody.builder().fid(2).geometry(mp).build();
        WaterBody result = classifier.classify(wb);

        assertEquals(WaterBodyType.RIVER, result.getWaterType());
    }

    @Test
    void testOceanClassification() {
        // 创建一个巨大的多边形 → 应分类为海洋
        MultiPolygon mp = createCirclePolygon(600_000); // 半径 600km → 面积 ~1,130,973 km² > 1,000,000
        WaterBody wb = WaterBody.builder().fid(3).geometry(mp).build();
        WaterBody result = classifier.classify(wb);

        assertEquals(WaterBodyType.OCEAN, result.getWaterType());
    }

    @Test
    void testHierarchyLevel() {
        // Level-4 (small): < 100 km²
        MultiPolygon small = createCirclePolygon(5_000); // ~78.5 km²
        assertEquals(4, classifier.classify(WaterBody.builder().geometry(small).build()).getHierarchyLevel());

        // Level-3 (medium): ~314 km²
        MultiPolygon medium = createCirclePolygon(10_000);
        assertEquals(3, classifier.classify(WaterBody.builder().geometry(medium).build()).getHierarchyLevel());

        // Level-2 (large): ~31,416 km²
        MultiPolygon large = createCirclePolygon(100_000);
        assertEquals(2, classifier.classify(WaterBody.builder().geometry(large).build()).getHierarchyLevel());
    }

    // ========== 辅助构造几何 ==========

    private static MultiPolygon createCirclePolygon(double radiusMeters) {
        Coordinate[] coords = new Coordinate[33];
        for (int i = 0; i < 32; i++) {
            double angle = 2 * Math.PI * i / 32;
            coords[i] = new Coordinate(
                    radiusMeters * Math.cos(angle),
                    radiusMeters * Math.sin(angle)
            );
        }
        coords[32] = coords[0]; // 闭合
        LinearRing shell = GF.createLinearRing(coords);
        Polygon polygon = GF.createPolygon(shell);
        return GF.createMultiPolygon(new Polygon[]{polygon});
    }

    private static MultiPolygon createNarrowPolygon() {
        // 200m × 5000m 的狭长矩形
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(0, 0),
                new Coordinate(200, 0),
                new Coordinate(200, 5000),
                new Coordinate(0, 5000),
                new Coordinate(0, 0)
        };
        LinearRing shell = GF.createLinearRing(coords);
        Polygon polygon = GF.createPolygon(shell);
        return GF.createMultiPolygon(new Polygon[]{polygon});
    }
}
