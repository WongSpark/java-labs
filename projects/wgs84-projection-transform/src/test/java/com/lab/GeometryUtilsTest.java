package com.lab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTReader;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GeometryUtils 工具方法单元测试
 * 使用 WKT 构造模拟几何数据进行验证
 */
class GeometryUtilsTest {

    private final GeometryFactory factory = new GeometryFactory();
    private final WKTReader reader = new WKTReader(factory);

    // ==================== normalizeLongitude ====================

    /**
     * 测试: 经度已在 [-180, 180] 范围内的情况
     * 场景: 输入 POINT(120 30)，经度 120 本身在合法范围内
     * 验证: 经度和纬度都不应发生变化
     */
    @Test
    @DisplayName("normalizeLongitude: coordinates already in [-180,180] stay unchanged")
    void normalizeLongitude_withinRange() throws Exception {
        Geometry geom = reader.read("POINT(120 30)");
        Geometry result = GeometryUtils.normalizeLongitude(geom);
        Coordinate coord = result.getCoordinate();
        assertEquals(120.0, coord.x, 1e-10);
        assertEquals(30.0, coord.y, 1e-10);
    }

    /**
     * 测试: 经度正向溢出（> 180）的情况
     * 场景: 输入 POINT(190 45)，190° 超出 180° 上限
     * 边界: Math.IEEEremainder(190, 360) = 190 - 360 = -170
     * 验证: 190° 应归化到 -170°，纬度不变
     */
    @Test
    @DisplayName("normalizeLongitude: positive overflow (>180) normalized to (-180,180]")
    void normalizeLongitude_positiveOverflow() throws Exception {
        Geometry geom = reader.read("POINT(190 45)");
        Geometry result = GeometryUtils.normalizeLongitude(geom);
        Coordinate coord = result.getCoordinate();
        assertEquals(-170.0, coord.x, 1e-10, "190 should normalize to -170");
    }

    /**
     * 测试: 经度负向溢出（< -180）的情况
     * 场景: 输入 POINT(-190 -45)，-190° 超出 -180° 下限
     * 边界: Math.IEEEremainder(-190, 360) = -190 + 360 = 170
     * 验证: -190° 应归化到 170°，纬度不变
     */
    @Test
    @DisplayName("normalizeLongitude: negative overflow (<-180) normalized to (-180,180]")
    void normalizeLongitude_negativeOverflow() throws Exception {
        Geometry geom = reader.read("POINT(-190 -45)");
        Geometry result = GeometryUtils.normalizeLongitude(geom);
        Coordinate coord = result.getCoordinate();
        assertEquals(170.0, coord.x, 1e-10, "-190 should normalize to 170");
    }

    /**
     * 测试: 经度为 360° 的特殊边界
     * 场景: 输入 POINT(360 0)，360° 等价于 0° 经线
     * 边界: Math.IEEEremainder(360, 360) = 0
     * 验证: 360° 应归化到 0°
     */
    @Test
    @DisplayName("normalizeLongitude: 360 normalizes to 0")
    void normalizeLongitude_360() throws Exception {
        Geometry geom = reader.read("POINT(360 0)");
        Geometry result = GeometryUtils.normalizeLongitude(geom);
        Coordinate coord = result.getCoordinate();
        assertEquals(0.0, coord.x, 1e-10, "360 should normalize to 0");
    }

    /**
     * 测试: 多节点 LineString 的经度归化
     * 场景: 输入 LINESTRING(190 0, -190 10, 370 20)，三个节点都超出范围
     * 边界:
     *   - 190 -> -170 (正溢出)
     *   - -190 -> 170 (负溢出)
     *   - 370 -> 10 (超出 360，回绕)
     * 验证: 所有节点的经度都被正确归化，纬度保持不变
     */
    @Test
    @DisplayName("normalizeLongitude: all nodes in LineString get normalized")
    void normalizeLongitude_lineString() throws Exception {
        Geometry geom = reader.read("LINESTRING(190 0, -190 10, 370 20)");
        Geometry result = GeometryUtils.normalizeLongitude(geom);
        Coordinate[] coords = result.getCoordinates();
        assertEquals(-170.0, coords[0].x, 1e-10);
        assertEquals(170.0, coords[1].x, 1e-10);
        assertEquals(10.0, coords[2].x, 1e-10, "370 -> 10");
    }

    /**
     * 测试: null 输入的安全性
     * 场景: 传入 null 几何对象
     * 验证: 返回 null，不抛 NullPointerException
     */
    @Test
    @DisplayName("normalizeLongitude: null returns null")
    void normalizeLongitude_null() {
        assertNull(GeometryUtils.normalizeLongitude(null));
    }

    /**
     * 测试: 方法的不可变性（不修改原始几何）
     * 场景: 输入经度 190 的点，调用 normalizeLongitude
     * 验证:
     *   - 原始几何的经度仍为 190（未被修改）
     *   - 返回的新几何经度为 -170
     * 目的: 确认方法内部使用了 copy()，符合函数式无副作用的设计
     */
    @Test
    @DisplayName("normalizeLongitude: does not modify original geometry")
    void normalizeLongitude_immutable() throws Exception {
        Geometry geom = reader.read("POINT(190 0)");
        Geometry result = GeometryUtils.normalizeLongitude(geom);
        assertEquals(190.0, geom.getCoordinate().x, 1e-10, "original should stay unchanged");
        assertEquals(-170.0, result.getCoordinate().x, 1e-10);
    }

    // ==================== crossesAntimeridian ====================

    /**
     * 测试: 普通线段不跨越日更线
     * 场景: 两点经度分别为 100 和 120，差值为 20 < 180
     * 验证: 返回 false
     */
    @Test
    @DisplayName("crossesAntimeridian: normal segment does not cross")
    void crossesAntimeridian_normal() throws Exception {
        Geometry geom = reader.read("LINESTRING(100 0, 120 10)");
        assertFalse(GeometryUtils.crossesAntimeridian(geom));
    }

    /**
     * 测试: 线段从正经度跨越到负经度，越过 180° 日更线
     * 场景: 两点经度为 170 和 -170，差值的绝对值为 340 > 180
     * 边界: 这是 GIS 数据中跨越日更线的典型表示方式
     * 验证: 返回 true
     */
    @Test
    @DisplayName("crossesAntimeridian: 170 to -170 crosses 180 meridian")
    void crossesAntimeridian_positiveToNegative() throws Exception {
        Geometry geom = reader.read("LINESTRING(170 0, -170 0)");
        assertTrue(GeometryUtils.crossesAntimeridian(geom));
    }

    /**
     * 测试: 线段从负经度跨越到正经度，越过 180° 日更线
     * 场景: 两点经度为 -170 和 170，差值的绝对值为 340 > 180
     * 边界: 反向跨越同样应被检测到
     * 验证: 返回 true
     */
    @Test
    @DisplayName("crossesAntimeridian: -170 to 170 crosses 180 meridian")
    void crossesAntimeridian_negativeToPositive() throws Exception {
        Geometry geom = reader.read("LINESTRING(-170 0, 170 0)");
        assertTrue(GeometryUtils.crossesAntimeridian(geom));
    }

    /**
     * 测试: 多边形跨越日更线的检测
     * 场景: 多边形从 170E 跨越到 170W（表示为 -170），覆盖日更线区域
     * 验证: 返回 true（多边形也应被正确检测）
     */
    @Test
    @DisplayName("crossesAntimeridian: polygon crossing 180 deg")
    void crossesAntimeridian_polygon() throws Exception {
        Geometry geom = reader.read("POLYGON((170 -10, -170 -10, -170 10, 170 10, 170 -10))");
        assertTrue(GeometryUtils.crossesAntimeridian(geom));
    }

    /**
     * 测试: 经度差恰好为 180° 但不跨越日更线的临界情况
     * 场景: 两点经度为 -180 和 0，差值为 180，但这是从 -180 到 0
     * 边界: 差值 180 是严格边界，> 180 才算跨越，等于不算
     * 验证: 返回 false
     */
    @Test
    @DisplayName("crossesAntimeridian: exactly 180 difference does NOT count")
    void crossesAntimeridian_exactly180() throws Exception {
        Geometry geom = reader.read("LINESTRING(-180 0, 0 0)");
        assertFalse(GeometryUtils.crossesAntimeridian(geom), "exactly 180 diff should not count");
    }

    /**
     * 测试: 单点几何的检测
     * 场景: 输入 POINT，只有一个坐标点，没有相邻点可比较
     * 边界: 只有 0 或 1 个坐标点的几何，循环不会执行
     * 验证: 返回 false
     */
    @Test
    @DisplayName("crossesAntimeridian: single point never crosses")
    void crossesAntimeridian_singlePoint() throws Exception {
        Geometry geom = reader.read("POINT(100 0)");
        assertFalse(GeometryUtils.crossesAntimeridian(geom));
    }

    /**
     * 测试: null 输入的安全性
     * 场景: 传入 null 几何对象
     * 验证: 返回 false，不抛 NullPointerException
     */
    @Test
    @DisplayName("crossesAntimeridian: null returns false")
    void crossesAntimeridian_null() {
        assertFalse(GeometryUtils.crossesAntimeridian(null));
    }

    // ==================== splitAntimeridian ====================

    /**
     * 测试: 跨越日更线的多边形被正确切割
     * 场景: 输入从 170E 到 170W 跨越日更线的面
     * 边界: 切割后面应变成 MultiPolygon，包含 2 个子面
     * 验证:
     *   - 结果不为 null
     *   - 几何类型为 MultiPolygon
     *   - 子几何数量为 2
     *   - 所有子面坐标的经度都在 [-180, 180] 合法范围内
     */
    @Test
    @DisplayName("splitAntimeridian: crossing polygon gets split into MultiPolygon")
    void splitAntimeridian_polygon() throws Exception {
        Geometry poly = reader.read("POLYGON((170 -10, -170 -10, -170 10, 170 10, 170 -10))");
        Geometry result = GeometryUtils.splitAntimeridian(poly);

        assertNotNull(result);
        assertEquals("MultiPolygon", result.getGeometryType(),
            "crossing polygon should become MultiPolygon");
        assertEquals(2, result.getNumGeometries(), "should produce 2 sub-geometries");

        for (int i = 0; i < result.getNumGeometries(); i++) {
            for (Coordinate c : result.getGeometryN(i).getCoordinates()) {
                assertTrue(c.x >= -180 && c.x <= 180,
                    "longitude " + c.x + " should be in [-180, 180]");
            }
        }
    }

    /**
     * 测试: 跨越日更线的 LineString 被正确切割
     * 场景: 输入从 170 到 -170 跨越日更线的线段
     * 验证: 结果不为 null，且包含至少 1 个子几何
     */
    @Test
    @DisplayName("splitAntimeridian: crossing LineString gets split")
    void splitAntimeridian_lineString() throws Exception {
        Geometry line = reader.read("LINESTRING(170 -10, -170 10)");
        Geometry result = GeometryUtils.splitAntimeridian(line);

        assertNotNull(result);
        assertTrue(result.getNumGeometries() >= 1, "should contain at least 1 sub-geometry");
    }

    /**
     * 测试: 不跨越日更线的几何保持不变
     * 场景: 输入一个普通的小矩形（经度 10 到 20），完全不靠近日更线
     * 验证: 结果类型仍为 Polygon，几何数量仍为 1（没有被提升为 MultiPolygon）
     * 目的: 确保方法对不跨越的几何不会误切
     */
    @Test
    @DisplayName("splitAntimeridian: non-crossing geometry stays single")
    void splitAntimeridian_notCrossing() throws Exception {
        Geometry poly = reader.read("POLYGON((10 10, 20 10, 20 20, 10 20, 10 10))");
        Geometry result = GeometryUtils.splitAntimeridian(poly);

        assertNotNull(result);
        assertEquals("Polygon", result.getGeometryType(), "non-crossing should not become MultiPolygon");
        assertEquals(1, result.getNumGeometries());
    }

    /**
     * 测试: null 输入的安全性
     * 场景: 传入 null 几何对象
     * 验证: 返回 null，不抛 NullPointerException
     */
    @Test
    @DisplayName("splitAntimeridian: null returns null")
    void splitAntimeridian_null() {
        assertNull(GeometryUtils.splitAntimeridian(null));
    }

    // ==================== clampLatitude ====================

    /**
     * 测试: 纬度在 [minLat, maxLat] 范围内保持不变
     * 场景: 纬度 45 在 [-85.06, 85.06] 范围内
     * 验证: 纬度不变，经度也不变
     */
    @Test
    @DisplayName("clampLatitude: latitude within range stays unchanged")
    void clampLatitude_withinRange() throws Exception {
        Geometry geom = reader.read("POINT(100 45)");
        Geometry result = GeometryUtils.clampLatitude(geom, -85.06, 85.06);
        assertEquals(45.0, result.getCoordinate().y, 1e-10);
    }

    /**
     * 测试: 纬度超过上限被截断
     * 场景: 纬度 90° 超过 Web Mercator 有效范围上限 85.06°
     * 边界: 极点附近投影到无穷大，在 3857 投影前必须截断
     * 验证: 纬度被截断到 85.06
     */
    @Test
    @DisplayName("clampLatitude: latitude above max gets clamped")
    void clampLatitude_aboveMax() throws Exception {
        Geometry geom = reader.read("POINT(100 90)");
        Geometry result = GeometryUtils.clampLatitude(geom, -85.06, 85.06);
        assertEquals(85.06, result.getCoordinate().y, 1e-10, "lat 90 should be clamped to 85.06");
    }

    /**
     * 测试: 纬度低于下限被截断
     * 场景: 纬度 -90° 低于 Web Mercator 有效范围下限 -85.06°
     * 验证: 纬度被截断到 -85.06
     */
    @Test
    @DisplayName("clampLatitude: latitude below min gets clamped")
    void clampLatitude_belowMin() throws Exception {
        Geometry geom = reader.read("POINT(100 -90)");
        Geometry result = GeometryUtils.clampLatitude(geom, -85.06, 85.06);
        assertEquals(-85.06, result.getCoordinate().y, 1e-10, "lat -90 should be clamped to -85.06");
    }

    /**
     * 测试: LineString 中部分节点超出纬度范围
     * 场景: 四个节点中，第二个纬度 90（超高）、第三个 -90（超低）、第一和第四在范围内
     * 边界: 在一个几何中混合了正常和超限的节点
     * 验证: 超限的被截断，在范围内的保持不变
     */
    @Test
    @DisplayName("clampLatitude: LineString with mixed out-of-range nodes")
    void clampLatitude_partialOverflow() throws Exception {
        Geometry geom = reader.read("LINESTRING(0 0, 0 90, 0 -90, 0 45)");
        Geometry result = GeometryUtils.clampLatitude(geom, -85.06, 85.06);
        Coordinate[] coords = result.getCoordinates();
        assertEquals(0.0, coords[0].y, 1e-10);
        assertEquals(85.06, coords[1].y, 1e-10, "90 clamped");
        assertEquals(-85.06, coords[2].y, 1e-10, "-90 clamped");
        assertEquals(45.0, coords[3].y, 1e-10, "45 stays");
    }

    /**
     * 测试: 方法的不可变性（不修改原始几何）
     * 场景: 输入纬度 90 的点，调用 clampLatitude 截断
     * 验证: 原始几何的纬度仍为 90，返回的新几何纬度为 85.06
     */
    @Test
    @DisplayName("clampLatitude: does not modify original geometry")
    void clampLatitude_immutable() throws Exception {
        Geometry geom = reader.read("POINT(100 90)");
        Geometry result = GeometryUtils.clampLatitude(geom, -85.06, 85.06);
        assertEquals(90.0, geom.getCoordinate().y, 1e-10, "original should stay unchanged");
        assertEquals(85.06, result.getCoordinate().y, 1e-10);
    }

    // ==================== collectParts (package-private) ====================

    /**
     * 测试: 从 GeometryCollection 中收集非空子几何
     * 场景: 构造一个包含 2 个 Point 的 GeometryCollection
     * 验证: parts 列表大小为 2
     */
    @Test
    @DisplayName("collectParts: gather non-empty sub-geometries from Collection")
    void collectParts_fromCollection() {
        Geometry geom = factory.createGeometryCollection(new Geometry[]{
            factory.createPoint(new Coordinate(1, 2)),
            factory.createPoint(new Coordinate(3, 4))
        });
        List<Geometry> parts = new ArrayList<>();
        GeometryUtils.collectParts(geom, parts);
        assertEquals(2, parts.size());
    }

    /**
     * 测试: 空几何不会添加到列表中
     * 场景: 传入空 Point，列表中已有 1 个有效几何
     * 边界: 空几何用 createPoint((Coordinate) null) 构造
     * 验证: 列表大小仍为 1（空几何被跳过）
     */
    @Test
    @DisplayName("collectParts: empty geometry is skipped")
    void collectParts_skipEmpty() {
        Geometry empty = factory.createPoint((Coordinate) null);
        List<Geometry> parts = new ArrayList<>();
        parts.add(factory.createPoint(new Coordinate(10, 20)));
        GeometryUtils.collectParts(empty, parts);
        assertEquals(1, parts.size(), "empty should not be added");
    }

    /**
     * 测试: null 输入的安全性
     * 场景: 传入 null，parts 列表为空
     * 验证: 列表仍为空，不抛异常
     */
    @Test
    @DisplayName("collectParts: null does not throw")
    void collectParts_null() {
        List<Geometry> parts = new ArrayList<>();
        GeometryUtils.collectParts(null, parts);
        assertTrue(parts.isEmpty());
    }

    // ==================== buildSplitResult (package-private) ====================

    /**
     * 测试: 只包含 1 个 part 时直接返回该 part
     * 场景: parts 列表中只有 1 个几何
     * 边界: 无需组装为 Multi 类型，直接返回单个几何
     * 验证: 返回的对象就是输入的 part 本身（同一引用）
     */
    @Test
    @DisplayName("buildSplitResult: single part returned directly")
    void buildSplitResult_singlePart() {
        Geometry part = factory.createPoint(new Coordinate(1, 2));
        List<Geometry> parts = List.of(part);
        Geometry original = factory.createPolygon();
        Geometry result = GeometryUtils.buildSplitResult(parts, original, factory);
        assertSame(part, result, "single part should be returned directly");
    }

    /**
     * 测试: 多个 Polygon part 组装为 MultiPolygon
     * 场景: 2 个独立的 Polygon parts
     * 原始几何类型影响方法的组装逻辑
     * 验证: 结果为 MultiPolygon，包含 2 个子几何
     */
    @Test
    @DisplayName("buildSplitResult: Polygon parts assembled into MultiPolygon")
    void buildSplitResult_multiPolygon() {
        Polygon p1 = factory.createPolygon(new Coordinate[]{
            new Coordinate(10, 10), new Coordinate(20, 10),
            new Coordinate(20, 20), new Coordinate(10, 20), new Coordinate(10, 10)
        });
        Polygon p2 = factory.createPolygon(new Coordinate[]{
            new Coordinate(-20, 10), new Coordinate(-10, 10),
            new Coordinate(-10, 20), new Coordinate(-20, 20), new Coordinate(-20, 10)
        });
        List<Geometry> parts = List.of(p1, p2);
        Geometry original = factory.createMultiPolygon();
        Geometry result = GeometryUtils.buildSplitResult(parts, original, factory);
        assertEquals("MultiPolygon", result.getGeometryType());
        assertEquals(2, result.getNumGeometries());
    }

    /**
     * 测试: 多个 LineString part 组装为 MultiLineString
     * 场景: 2 条在日更线两侧的线段 parts（左段 170->180，右段 -180->-170）
     * 验证: 结果为 MultiLineString，包含 2 个子几何
     */
    @Test
    @DisplayName("buildSplitResult: LineString parts assembled into MultiLineString")
    void buildSplitResult_multiLineString() {
        LineString l1 = factory.createLineString(new Coordinate[]{
            new Coordinate(170, 0), new Coordinate(180, 10)
        });
        LineString l2 = factory.createLineString(new Coordinate[]{
            new Coordinate(-180, 10), new Coordinate(-170, 0)
        });
        List<Geometry> parts = List.of(l1, l2);
        Geometry original = factory.createMultiLineString();
        Geometry result = GeometryUtils.buildSplitResult(parts, original, factory);
        assertEquals("MultiLineString", result.getGeometryType());
        assertEquals(2, result.getNumGeometries());
    }

    // ==================== 综合场景 ====================

    /**
     * 综合集成测试: 跨越日更线的多边形经过完整 pipeline 处理
     * 场景: 一个跨越日更线的多边形，依次调用:
     *   1. normalizeLongitude — 经度标准化
     *   2. crossesAntimeridian — 检测跨越
     *   3. splitAntimeridian — 切割
     *   4. clampLatitude — 纬度截断
     * 验证:
     *   - 每一步都正确执行
     *   - 最终结果的经度在 [-180, 180] 范围内
     *   - 最终结果的纬度在 [-85.06, 85.06] 范围内
     * 目的: 确保方法链组合使用时行为正确
     */
    @Test
    @DisplayName("pipeline: crossing polygon through full pipeline")
    void pipeline_crossingPolygon() throws Exception {
        Geometry poly = reader.read("POLYGON((170 -10, -170 -10, -170 10, 170 10, 170 -10))");

        Geometry normalized = GeometryUtils.normalizeLongitude(poly);
        assertNotNull(normalized);

        assertTrue(GeometryUtils.crossesAntimeridian(normalized));

        Geometry split = GeometryUtils.splitAntimeridian(normalized);
        assertEquals("MultiPolygon", split.getGeometryType());
        assertEquals(2, split.getNumGeometries());

        Geometry clamped = GeometryUtils.clampLatitude(split, -85.06, 85.06);
        assertEquals(split.getNumGeometries(), clamped.getNumGeometries());

        for (Coordinate c : clamped.getCoordinates()) {
            assertTrue(c.x >= -180 && c.x <= 180,
                "longitude " + c.x + " should be in [-180, 180]");
            assertTrue(c.y >= -85.06 && c.y <= 85.06,
                "latitude " + c.y + " should be in [-85.06, 85.06]");
        }
    }
}
