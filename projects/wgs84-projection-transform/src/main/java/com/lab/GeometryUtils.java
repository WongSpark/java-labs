package com.lab;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.union.UnaryUnionOp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JTS 几何图形工具方法：经度标准化、日更线切割、纬度截断
 */
@Slf4j
public class GeometryUtils {

    /**
     * 将所有坐标点的经度标准化到 [-180, 180] 范围
     */
    public static Geometry normalizeLongitude(Geometry geom) {
        if (geom == null) return null;
        Geometry copy = geom.copy();
        copy.apply((CoordinateFilter) coord -> coord.x = Math.IEEEremainder(coord.x, 360));
        copy.geometryChanged();
        return copy;
    }

    /**
     * 检查几何图形是否跨越日更线（180° 经线）
     */
    public static boolean crossesAntimeridian(Geometry geom) {
        if (geom == null) return false;
        Coordinate[] coords = geom.getCoordinates();
        for (int i = 0; i < coords.length - 1; i++) {
            if (Math.abs(coords[i + 1].x - coords[i].x) > 180) {
                return true;
            }
        }
        return false;
    }

    /**
     * 切割跨越日更线的几何图形
     */
    public static Geometry splitAntimeridian(Geometry geom) {
        if (geom == null) return null;
        try {
            GeometryFactory factory = geom.getFactory();

            if (!geom.isValid()) {
                geom = geom.buffer(0);
            }

            // 将经度平移到 [0, 360)，此时日更线位于 180°
            Geometry shifted = geom.copy();
            shifted.apply((CoordinateFilter) coord -> coord.x = ((coord.x % 360) + 360) % 360);
            shifted.geometryChanged();

            // 在 180° 处切割，微小间隙确保两部分不共享边界
            final double eps = 1e-6;

            Polygon leftClip = factory.createPolygon(new Coordinate[]{
                new Coordinate(0, -90), new Coordinate(180 - eps, -90),
                new Coordinate(180 - eps, 90), new Coordinate(0, 90),
                new Coordinate(0, -90)
            });
            Polygon rightClip = factory.createPolygon(new Coordinate[]{
                new Coordinate(180 + eps, -90), new Coordinate(360, -90),
                new Coordinate(360, 90), new Coordinate(180 + eps, 90),
                new Coordinate(180 + eps, -90)
            });

            Geometry leftPart = shifted.intersection(leftClip);
            Geometry rightPart = shifted.intersection(rightClip);

            // 右侧部分 [180, 360] 平移回 [-180, 0]
            if (!rightPart.isEmpty()) {
                rightPart.apply((CoordinateFilter) coord -> coord.x -= 360);
                rightPart.geometryChanged();
            }

            List<Geometry> parts = new ArrayList<>();
            collectParts(leftPart, parts);
            collectParts(rightPart, parts);

            if (parts.isEmpty()) {
                return geom;
            }

            return buildSplitResult(parts, geom, factory);
        } catch (Exception e) {
            log.warn("日更线切割失败，保留原始几何: {}", e.getMessage());
            return geom;
        }
    }

    /**
     * 将数据提供方在 180° 处预拆分的 MultiPolygon 合并回单一 Polygon。
     * 对于 3411/3412 极地投影，180° 经线不是物理边界，拆分反而产生视觉缝隙。
     *
     * 使用端点匹配拼接算法：
     * 1. 负经度部分整体 x += 360，与正经度部分在 180° 处连续
     * 2. 去掉两个环的闭合顶点，得到开放路径
     * 3. 匹配开放路径在 180° 线上的端点，拼接为单一闭合环
     * 4. 若拼接失败，回退到 union
     */
    public static Geometry mergeAntimeridianSplit(Geometry geom) {
        if (geom == null) return null;
        if (!(geom instanceof MultiPolygon)) return geom;

        try {
            GeometryFactory factory = geom.getFactory();

            // 检查是否有负经度部分
            boolean hasNegativeX = false;
            for (int i = 0; i < geom.getNumGeometries() && !hasNegativeX; i++) {
                for (Coordinate c : geom.getGeometryN(i).getCoordinates()) {
                    if (c.x < 0) { hasNegativeX = true; break; }
                }
            }
            if (!hasNegativeX) return geom;

            // 提取所有多边形：负经度部分 x += 360
            List<Polygon> parts = new ArrayList<>();
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                Geometry part = geom.getGeometryN(i);
                List<Polygon> polys = new ArrayList<>();
                extractPolygons(part, polys);
                for (Polygon p : polys) {
                    boolean neg = false;
                    for (Coordinate c : p.getCoordinates()) {
                        if (c.x < 0) { neg = true; break; }
                    }
                    if (neg) {
                        Geometry shifted = p.copy();
                        shifted.apply((CoordinateFilter) coord -> coord.x += 360);
                        shifted.geometryChanged();
                        extractPolygons(shifted, parts);
                    } else {
                        parts.add(p);
                    }
                }
            }

            // 尝试端点匹配拼接
            Geometry stitched = stitchByEndpointMatch(parts, factory);
            if (stitched != null) {
                return normalizeLongitude(stitched);
            }

            // 拼接失败，回退到 union
            log.debug("顶点拼接失败，回退到 union");
            Geometry merged = UnaryUnionOp.union(parts);
            if (!merged.isEmpty()) {
                if (merged instanceof MultiPolygon) {
                    merged = merged.buffer(0);
                }
                return normalizeLongitude(merged);
            }
            return geom;

        } catch (Exception e) {
            log.warn("反合并日更线拆分失败，保留原始几何: {}", e.getMessage());
            return geom;
        }
    }

    /** 180° 线上缝合端点匹配容差 */
    private static final double SEAM_TOLERANCE = 1e-4;

    /**
     * 判断两点是否位于 180° 线上的同一缝端点。
     */
    private static boolean isSeamEndpoint(Coordinate a, Coordinate b) {
        return Math.abs(a.x - 180.0) < SEAM_TOLERANCE
            && Math.abs(b.x - 180.0) < SEAM_TOLERANCE
            && Math.abs(a.y - b.y) < SEAM_TOLERANCE;
    }

    /**
     * 基于端点匹配将两个 Polygon 拼接为单一 Polygon。
     *
     * 算法：去掉两个环的闭合顶点 → 开放路径，匹配 180° 缝端点，
     * 按匹配关系拼接（必要时翻转），最后闭合。
     */
    private static Geometry stitchByEndpointMatch(List<Polygon> parts, GeometryFactory factory) {
        if (parts.size() != 2) return null;

        // 去掉闭合顶点，得到开放路径
        Coordinate[] ring0 = parts.get(0).getExteriorRing().getCoordinates();
        Coordinate[] ring1 = parts.get(1).getExteriorRing().getCoordinates();
        Coordinate[] open0 = Arrays.copyOf(ring0, ring0.length - 1);
        Coordinate[] open1 = Arrays.copyOf(ring1, ring1.length - 1);

        Coordinate s0 = open0[0], e0 = open0[open0.length - 1];
        Coordinate s1 = open1[0], e1 = open1[open1.length - 1];

        Coordinate[] merged = null;

        if (isSeamEndpoint(e0, s1)) {
            // P1尾 → P2头
            merged = concatOpenPaths(open0, open1, s0);
        } else if (isSeamEndpoint(e0, e1)) {
            // P1尾 → P2尾：翻转 P2
            merged = concatOpenPaths(open0, reverseCopy(open1), s0);
        } else if (isSeamEndpoint(s0, e1)) {
            // P1头 → P2尾：交换顺序
            merged = concatOpenPaths(open1, open0, s1);
        } else if (isSeamEndpoint(s0, s1)) {
            // P1头 → P2头：翻转 P1
            Coordinate[] rev0 = reverseCopy(open0);
            merged = concatOpenPaths(rev0, open1, rev0[0]);
        } else {
            return null; // 无法匹配，回退 union
        }

        try {
            LinearRing shell = factory.createLinearRing(merged);
            List<LinearRing> holes = new ArrayList<>();
            for (Polygon p : parts) {
                for (int i = 0; i < p.getNumInteriorRing(); i++) {
                    holes.add((LinearRing) p.getInteriorRingN(i).copy());
                }
            }
            if (!holes.isEmpty()) {
                return factory.createPolygon(shell, holes.toArray(new LinearRing[0]));
            }
            return factory.createPolygon(shell);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 拼接两条开放路径：pathA（全部）+ pathB（跳过首顶点，避免重复）+ 闭合。
     */
    private static Coordinate[] concatOpenPaths(Coordinate[] a, Coordinate[] b, Coordinate close) {
        Coordinate[] result = new Coordinate[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 1, result, a.length, b.length - 1);
        result[result.length - 1] = new Coordinate(close);
        return result;
    }

    /**
     * 创建坐标数组的反向副本。
     */
    private static Coordinate[] reverseCopy(Coordinate[] coords) {
        int n = coords.length;
        Coordinate[] rev = new Coordinate[n];
        for (int i = 0; i < n; i++) {
            rev[i] = coords[n - 1 - i];
        }
        return rev;
    }

    /**
     * 将几何图形裁剪到北半球（纬度 >= 0），剔除南半球部分。
     * 用于 EPSG:3411 等北极投影——南半球数据无意义。
     * @return 裁剪后的几何，若完全不在北半球则返回 null
     */
    public static Geometry clipToNorthernHemisphere(Geometry geom) {
        return clipToHemisphere(geom, 0, 90);
    }

    /**
     * 将几何图形裁剪到南半球（纬度 <= 0），剔除北半球部分。
     * 用于 EPSG:3412 等南极投影——北半球数据无意义。
     * @return 裁剪后的几何，若完全不在南半球则返回 null
     */
    public static Geometry clipToSouthernHemisphere(Geometry geom) {
        return clipToHemisphere(geom, -90, 0);
    }

    private static Geometry clipToHemisphere(Geometry geom, double minLat, double maxLat) {
        if (geom == null) return null;
        try {
            GeometryFactory factory = geom.getFactory();
            Geometry clipPoly = factory.createPolygon(new Coordinate[]{
                new Coordinate(-180, minLat), new Coordinate(180, minLat),
                new Coordinate(180, maxLat), new Coordinate(-180, maxLat),
                new Coordinate(-180, minLat)
            });
            Geometry result = geom.intersection(clipPoly);
            if (result.isEmpty()) return null;

            // 过滤掉非面状退化结果（如仅触及边界产生的 LineString/Point）
            List<Polygon> polys = new ArrayList<>();
            extractPolygons(result, polys);
            if (polys.isEmpty()) return null;       // 无线段/点 → 该半球无有效覆盖
            if (polys.size() == 1) return polys.get(0);
            return factory.createMultiPolygon(polys.toArray(new Polygon[0]));
        } catch (Exception e) {
            log.warn("半球裁剪失败，保留原始几何: {}", e.getMessage());
            return geom;
        }
    }

    /**
     * 将几何图形的纬度限制在指定范围内
     */
    public static Geometry clampLatitude(Geometry geom, double minLat, double maxLat) {
        Geometry copy = geom.copy();
        copy.apply((CoordinateFilter) coord -> {
            if (coord.y > maxLat) coord.y = maxLat;
            else if (coord.y < minLat) coord.y = minLat;
        });
        copy.geometryChanged();
        return copy;
    }

    static void collectParts(Geometry source, List<Geometry> parts) {
        if (source == null || source.isEmpty()) return;
        for (int i = 0; i < source.getNumGeometries(); i++) {
            Geometry g = source.getGeometryN(i);
            if (!g.isEmpty()) {
                parts.add(g);
            }
        }
    }

    static Geometry buildSplitResult(List<Geometry> parts, Geometry original, GeometryFactory factory) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        if (original instanceof Polygon || original instanceof MultiPolygon) {
            List<Polygon> polygons = new ArrayList<>();
            for (Geometry part : parts) {
                extractPolygons(part, polygons);
            }
            if (polygons.size() == 1) return polygons.get(0);
            return factory.createMultiPolygon(polygons.toArray(new Polygon[0]));
        }
        if (original instanceof LineString || original instanceof MultiLineString) {
            List<LineString> lines = new ArrayList<>();
            for (Geometry part : parts) {
                extractLines(part, lines);
            }
            if (lines.size() == 1) return lines.get(0);
            return factory.createMultiLineString(lines.toArray(new LineString[0]));
        }
        return factory.createGeometryCollection(parts.toArray(new Geometry[0]));
    }

    private static void extractPolygons(Geometry geom, List<Polygon> result) {
        if (geom instanceof Polygon) {
            result.add((Polygon) geom);
        } else if (geom instanceof MultiPolygon || geom instanceof GeometryCollection) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                extractPolygons(geom.getGeometryN(i), result);
            }
        }
    }

    private static void extractLines(Geometry geom, List<LineString> result) {
        if (geom instanceof LineString) {
            result.add((LineString) geom);
        } else if (geom instanceof MultiLineString || geom instanceof GeometryCollection) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                extractLines(geom.getGeometryN(i), result);
            }
        }
    }
}
