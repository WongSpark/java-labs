package com.lab;

import lombok.extern.slf4j.Slf4j;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geojson.feature.FeatureJSON;
import org.geotools.geojson.geom.GeometryJSON;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * GeoJSON 坐标转换器：读取输入 GeoJSON，转换到目标坐标系，写出结果
 */
@Slf4j
public class GeoJSONTransformer {

    /**
     * 将单个 GeoJSON 文件转换到目标坐标系
     * @param outputDir 输出目录（需已创建）
     */
    public static void transform(File inputFile, CoordinateReferenceSystem sourceCRS, String targetCode, File outputDir) {
        String outputFileName = inputFile.getName().replace(".json", "").replace(".geojson", "")
                                + "_" + targetCode.replace(":", "") + ".json";
        File outputFile = new File(outputDir, outputFileName);

        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(outputFile)) {

            log.info("  -> 转换到 {}: {}", targetCode, outputFileName);

            CoordinateReferenceSystem targetCRS = CRS.decode(targetCode);
            MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS, true);

            FeatureJSON fjson = new FeatureJSON(new GeometryJSON(15));
            FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection = fjson.readFeatureCollection(fis);

            SimpleFeatureType schema = featureCollection.getSchema();
            SimpleFeatureType targetType = buildTargetType(schema, targetCRS);
            SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(targetType);

            List<SimpleFeature> transformedFeatures = new ArrayList<>();

            try (FeatureIterator<SimpleFeature> iterator = featureCollection.features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Geometry sourceGeom = (Geometry) feature.getDefaultGeometry();

                    if (sourceGeom != null) {
                        // 0. 经度标准化：将所有经度归一化到 [-180, 180]
                        sourceGeom = GeometryUtils.normalizeLongitude(sourceGeom);

                        if ("EPSG:3857".equals(targetCode)) {
                            // 3857 (Web Mercator)：需要日更线拆分 + 纬度截断
                            if (GeometryUtils.crossesAntimeridian(sourceGeom)) {
                                log.info("检测到要素 {} 跨越日更线，正在进行切割...", feature.getID());
                                sourceGeom = GeometryUtils.splitAntimeridian(sourceGeom);
                                log.info("切割完成，结果类型: {}, 几何部件数: {}",
                                    sourceGeom.getGeometryType(), sourceGeom.getNumGeometries());
                            }
                            sourceGeom = GeometryUtils.clampLatitude(sourceGeom, -88, 88);
                        } else if ("EPSG:3411".equals(targetCode)) {
                            if(feature.getAttribute("firUirIdentifier").equals("UHMM")) {
                                log.info("检测到要素 {} 位于俄罗斯远东，正在进行特殊处理...", feature.getID());
                            }
                            // 3411 (北极立体投影)：先合并源数据预拆分的 MultiPolygon，再裁剪南半球
                            sourceGeom = GeometryUtils.mergeAntimeridianSplit(sourceGeom);
                            sourceGeom = GeometryUtils.clipToNorthernHemisphere(sourceGeom);
                            if (sourceGeom == null) {
                                log.debug("要素 {} 不在北半球，跳过 3411 转换", feature.getID());
                                continue;
                            }
                        } else if ("EPSG:3412".equals(targetCode)) {
                            // 3412 (南极立体投影)：先合并源数据预拆分的 MultiPolygon，再裁剪北半球
                            sourceGeom = GeometryUtils.mergeAntimeridianSplit(sourceGeom);
                            sourceGeom = GeometryUtils.clipToSouthernHemisphere(sourceGeom);
                            if (sourceGeom == null) {
                                log.debug("要素 {} 不在南半球，跳过 3412 转换", feature.getID());
                                continue;
                            }
                        }

                        // 执行几何转换
                        Geometry targetGeom = JTS.transform(sourceGeom, transform);
                        featureBuilder.addAll(feature.getAttributes());
                        featureBuilder.set(schema.getGeometryDescriptor().getLocalName(), targetGeom);
                        transformedFeatures.add(featureBuilder.buildFeature(feature.getID()));
                    }
                }
            }

            ListFeatureCollection targetCollection = new ListFeatureCollection(targetType, transformedFeatures);
            fjson.writeFeatureCollection(targetCollection, fos);

        } catch (Exception e) {
            log.error("    转换 {} 到 {} 失败: {}", inputFile.getName(), targetCode, e.getMessage());
        }
    }

    private static SimpleFeatureType buildTargetType(SimpleFeatureType schema, CoordinateReferenceSystem targetCRS) {
        SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
        typeBuilder.setName(schema.getName());
        typeBuilder.setCRS(targetCRS);

        String geomName = schema.getGeometryDescriptor().getLocalName();
        for (AttributeDescriptor desc : schema.getAttributeDescriptors()) {
            if (desc instanceof GeometryDescriptor) {
                typeBuilder.add(geomName, Geometry.class, targetCRS);
            } else {
                typeBuilder.add(desc.getLocalName(), desc.getType().getBinding());
            }
        }
        typeBuilder.setDefaultGeometry(geomName);
        return typeBuilder.buildFeatureType();
    }
}
