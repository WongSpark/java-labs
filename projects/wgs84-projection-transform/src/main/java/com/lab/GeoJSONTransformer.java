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
     */
    public static void transform(File inputFile, CoordinateReferenceSystem sourceCRS, String targetCode) {
        String outputFileName = inputFile.getName().replace(".json", "").replace(".geojson", "")
                                + "_" + targetCode.replace(":", "") + ".json";
        File outputFile = new File("output", outputFileName);

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

                        // 1. 检查是否跨越日更线，如果跨越则进行切割
                        if (GeometryUtils.crossesAntimeridian(sourceGeom)) {
                            log.info("检测到要素 {} 跨越日更线，正在进行切割...", feature.getID());
                            sourceGeom = GeometryUtils.splitAntimeridian(sourceGeom);
                            log.info("切割完成，结果类型: {}, 几何部件数: {}",
                                sourceGeom.getGeometryType(), sourceGeom.getNumGeometries());
                        }

                        // 2. 针对 EPSG:3857 纬度截断
                        if ("EPSG:3857".equals(targetCode)) {
                            sourceGeom = GeometryUtils.clampLatitude(sourceGeom, -85.06, 85.06);
                        }

                        // 3. 执行几何转换
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
