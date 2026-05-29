package com.lab;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.*;

import java.util.ArrayList;
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
