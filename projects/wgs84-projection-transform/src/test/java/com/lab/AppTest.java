package com.lab;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;

import static org.junit.jupiter.api.Assertions.*;

class AppTest {

    private final GeometryFactory factory = new GeometryFactory();
    private final WKTReader reader = new WKTReader(factory);

    @Test
    void testCrossesAntimeridian() throws Exception {
        // 1. 不跨越的情况 (宽度 < 180)
        Geometry normalRect = reader.read("POLYGON((10 10, 20 10, 20 20, 10 20, 10 10))");
        assertFalse(App.crossesAntimeridian(normalRect), "普通矩形不应被判定为跨越日更线");

        // 2. 跨越的情况 (通常在 GIS 数据中，跨越 180 的图形坐标可能被表示为 170 到 -170)
        // 在 JTS Cartesian 坐标系中，[170, -170] 的 Envelope 宽度计算取决于坐标顺序
        // 如果我们构造一个宽度明显大于 180 的 Envelope
        Geometry crossRect = reader.read("LINESTRING(170 0, -170 0)"); 
        // Envelope 会是 [-170, 170]，宽度 340
        assertTrue(App.crossesAntimeridian(crossRect), "跨越 180 度的线应被判定为跨越日更线");
    }

    @Test
    void testSplitAntimeridian() throws Exception {
        // 构造一个跨越日更线的面 (170E 到 170W)
        // 在 GeoJSON 中，跨越 180 的图形通常表示为从 170 到 -170
        Geometry poly = reader.read("POLYGON((170 -10, -170 -10, -170 10, 170 10, 170 -10))");
        
        assertTrue(App.crossesAntimeridian(poly), "跨越 170 到 -170 的面应被判定为跨越日更线");
        
        Geometry split = App.splitAntimeridian(poly);
        
        assertNotNull(split);
        // 切割后应该是 MultiPolygon
        assertEquals("MultiPolygon", split.getGeometryType());
        // 应该被切成两块
        assertEquals(2, split.getNumGeometries());
        
        System.out.println("Original: " + poly);
        System.out.println("Split: " + split);
    }
}
