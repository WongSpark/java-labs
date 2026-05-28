package com.lab;

import lombok.extern.slf4j.Slf4j;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geojson.feature.FeatureJSON;
import org.geotools.geojson.geom.GeometryJSON;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * GeoJSON 坐标系批量转换实验
 * 目标: 将 data/ 下的 4326 GeoJSON 转换为 3411, 3412, 3857
 */
@Slf4j
public class App {

    public static void main(String[] args) {
        // 1. 环境初始化
        // 将 Java Util Logging (JUL) 桥接到 SLF4J，解决 GeoTools 内部日志乱码和频率问题
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
        
        log.info("开始 GeoJSON 批量坐标转换实验");

        try {
            // 1. 环境初始化
            System.setProperty("org.geotools.referencing.forceXY", "true");
            CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:4326");
            String[] targetCodes = {"EPSG:3411", "EPSG:3412", "EPSG:3857"};

            // 2. 获取输入文件列表
            File dataDir = new File("data");
            File[] inputFiles = dataDir.listFiles((dir, name) -> name.endsWith(".json") || name.endsWith(".geojson"));

            if (inputFiles == null || inputFiles.length == 0) {
                log.warn("data 文件夹下未找到 GeoJSON 文件");
                return;
            }

            // 确保输出目录存在
            Path outputPath = Paths.get("output");
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            }

            // 3. 循环处理每个文件和每个目标坐标系
            for (File inputFile : inputFiles) {
                log.info("正在处理文件: {}", inputFile.getName());
                
                for (String targetCode : targetCodes) {
                    transformGeoJSON(inputFile, sourceCRS, targetCode);
                }
            }

        } catch (Exception e) {
            log.error("实验过程中发生错误", e);
        }

        log.info("实验结束");
        System.exit(0);
    }

    /**
     * 将所有坐标点的经度标准化到 [-180, 180] 范围
     */
    static Geometry normalizeLongitude(Geometry geom) {
        if (geom == null) return null;
        Geometry copy = geom.copy();
        copy.apply(new CoordinateFilter() {
            @Override
            public void filter(Coordinate coord) {
                coord.x = Math.IEEEremainder(coord.x, 360);
            }
        });
        copy.geometryChanged();
        return copy;
    }

    /**
     * 检查几何图形是否跨越日更线（180° 经线）
     * 遍历所有相邻坐标点，若任意线段两端经度差绝对值超过 180，则判定为跨越
     */
    static boolean crossesAntimeridian(Geometry geom) {
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
     * 切割跨越日更线的几何图形。
     * 将经度平移到 [0, 360) 范围后在 180°（日更线）处切割，
     * 右侧部分平移回 [-180, 0]，使两部分之间不共享边界。
     */
    static Geometry splitAntimeridian(Geometry geom) {
        if (geom == null) return null;
        try {
            GeometryFactory factory = geom.getFactory();

            if (!geom.isValid()) {
                geom = geom.buffer(0);
            }

            // 将经度平移到 [0, 360)，此时日更线位于 180°
            Geometry shifted = geom.copy();
            shifted.apply(new CoordinateFilter() {
                @Override
                public void filter(Coordinate coord) {
                    coord.x = ((coord.x % 360) + 360) % 360;
                }
            });
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
                rightPart.apply(new CoordinateFilter() {
                    @Override
                    public void filter(Coordinate coord) {
                        coord.x -= 360;
                    }
                });
                rightPart.geometryChanged();
            }

            // 收集并组装结果
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

    private static void collectParts(Geometry source, List<Geometry> parts) {
        if (source == null || source.isEmpty()) return;
        for (int i = 0; i < source.getNumGeometries(); i++) {
            Geometry g = source.getGeometryN(i);
            if (!g.isEmpty()) {
                parts.add(g);
            }
        }
    }

    private static Geometry buildSplitResult(List<Geometry> parts, Geometry original, GeometryFactory factory) {
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

    /**
     * 将几何图形的纬度限制在指定范围内，防止 Web Mercator 在极点附近发散
     */
    private static Geometry clampLatitude(Geometry geom, double minLat, double maxLat) {
        Geometry copy = geom.copy();
        copy.apply(new CoordinateFilter() {
            @Override
            public void filter(Coordinate coord) {
                if (coord.y > maxLat) {
                    coord.y = maxLat;
                } else if (coord.y < minLat) {
                    coord.y = minLat;
                }
            }
        });
        copy.geometryChanged();
        return copy;
    }

    private static void transformGeoJSON(File inputFile, CoordinateReferenceSystem sourceCRS, String targetCode) {
        String outputFileName = inputFile.getName().replace(".json", "").replace(".geojson", "") 
                                + "_" + targetCode.replace(":", "") + ".json";
        File outputFile = new File("output", outputFileName);

        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(outputFile)) {
            
            log.info("  -> 转换到 {}: {}", targetCode, outputFileName);
            
            CoordinateReferenceSystem targetCRS = CRS.decode(targetCode);
            MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS, true);

            FeatureJSON fjson = new FeatureJSON(new GeometryJSON(15)); // 保持高精度
            FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection = fjson.readFeatureCollection(fis);
            
            SimpleFeatureType schema = featureCollection.getSchema();

            // 创建新的 FeatureType (因为坐标系变了)
            // 使用 Geometry.class 作为几何属性绑定，允许 Polygon/MultiPolygon 等混合输出
            SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
            typeBuilder.setName(schema.getName());
            typeBuilder.setCRS(targetCRS);

            String geomName = schema.getGeometryDescriptor().getLocalName();
            for (org.opengis.feature.type.AttributeDescriptor desc : schema.getAttributeDescriptors()) {
                if (desc instanceof org.opengis.feature.type.GeometryDescriptor) {
                    typeBuilder.add(geomName, Geometry.class, targetCRS);
                } else {
                    typeBuilder.add(desc.getLocalName(), desc.getType().getBinding());
                }
            }
            typeBuilder.setDefaultGeometry(geomName);
            SimpleFeatureType targetType = typeBuilder.buildFeatureType();

            List<SimpleFeature> transformedFeatures = new ArrayList<>();
            SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(targetType);

            try (FeatureIterator<SimpleFeature> iterator = featureCollection.features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Geometry sourceGeom = (Geometry) feature.getDefaultGeometry();
                    
                    if (sourceGeom != null) {
                        // 0. 经度标准化：将所有经度归一化到 [-180, 180]，处理超出范围的数据
                        sourceGeom = normalizeLongitude(sourceGeom);

                        // 1. 检查是否跨越日更线，如果跨越则进行切割
                        if (crossesAntimeridian(sourceGeom)) {
                            log.info("检测到要素 {} 跨越日更线，正在进行切割...", feature.getID());
                            sourceGeom = splitAntimeridian(sourceGeom);
                            log.info("切割完成，结果类型: {}, 几何部件数: {}",
                                sourceGeom.getGeometryType(), sourceGeom.getNumGeometries());
                        }

                        // 2. 针对 EPSG:3857 的特殊处理：
                        // 纬度截断到 Web Mercator 有效范围，避免极点附近投影到无穷大
                        if ("EPSG:3857".equals(targetCode)) {
                            sourceGeom = clampLatitude(sourceGeom, -85.06, 85.06);
                        }

                        // 3. 执行几何转换
                        Geometry targetGeom = JTS.transform(sourceGeom, transform);
                        featureBuilder.addAll(feature.getAttributes());
                        featureBuilder.set(schema.getGeometryDescriptor().getLocalName(), targetGeom);
                        transformedFeatures.add(featureBuilder.buildFeature(feature.getID()));
                    }
                }
            }

            // 写入输出文件
            // 重新获取转换后的 collection
            // 由于 FeatureJSON 的限制，我们直接使用 List 写入可能不方便。
            // 这里我们使用 org.geotools.data.collection.ListFeatureCollection
            org.geotools.data.collection.ListFeatureCollection targetCollection = 
                new org.geotools.data.collection.ListFeatureCollection(targetType, transformedFeatures);
            
            fjson.writeFeatureCollection(targetCollection, fos);
            
        } catch (Exception e) {
            log.error("    转换 {} 到 {} 失败: {}", inputFile.getName(), targetCode, e.getMessage());
        }
    }
}
