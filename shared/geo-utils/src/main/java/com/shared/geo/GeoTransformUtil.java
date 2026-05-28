package com.shared.geo;

import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.locationtech.jts.geom.Point;

/**
 * 通用地理坐标转换工具类
 */
public class GeoTransformUtil {

    static {
        // 强制使用 经度/纬度 顺序
        System.setProperty("org.geotools.referencing.forceXY", "true");
    }

    /**
     * 坐标转换 (启用宽容模式)
     *
     * @param point      原始点
     * @param sourceEpsg 源 EPSG 代码 (如 "EPSG:4326")
     * @param targetEpsg 目标 EPSG 代码 (如 "EPSG:3857")
     * @return 转换后的点
     * @throws Exception 转换失败
     */
    public static Point transform(Point point, String sourceEpsg, String targetEpsg) throws Exception {
        CoordinateReferenceSystem sourceCRS = CRS.decode(sourceEpsg);
        CoordinateReferenceSystem targetCRS = CRS.decode(targetEpsg);
        // 默认开启 lenient=true 以处理 Bursa-Wolf 参数缺失问题
        MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS, true);
        return (Point) JTS.transform(point, transform);
    }
}
