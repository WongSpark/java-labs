package com.lab.water.io;

import com.lab.water.model.WaterBody;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.MultiPolygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * SHP 文件写入器 — 将分类结果按层级输出为独立的 Shapefile。
 *
 * <p>输出文件命名格式: {regionPrefix}_Water_{level}sqkm.shp</p>
 * <p>例如: AsiaEurope_Water_20sqkm.shp</p>
 */
public class ShapeFileWriter {

    private static final Logger log = LoggerFactory.getLogger(ShapeFileWriter.class);

    private final SimpleFeatureType featureType;

    public ShapeFileWriter() {
        this.featureType = createFeatureType();
    }

    /**
     * 将一组水体要素写入 SHP 文件。
     *
     * @param outputDir  输出目录
     * @param regionName 区域前缀（如 "AsiaEurope"）
     * @param levelLabel 层级标签（如 "20sqkm"）
     * @param bodies     待写入的水体列表
     */
    public File write(File outputDir, String regionName, String levelLabel, List<WaterBody> bodies)
            throws IOException {

        String fileName = String.format("%s_Water_%s.shp", regionName, levelLabel);
        File shpFile = new File(outputDir, fileName);

        SimpleFeatureCollection collection = toFeatureCollection(bodies);
        ShapeFileHelper.writeFeatureCollection(collection, shpFile);

        log.info("写入 SHP [{}] 完成，共 {} 个要素", fileName, bodies.size());
        return shpFile;
    }

    /**
     * 将 WaterBody 列表转为 GeoTools FeatureCollection
     */
    private SimpleFeatureCollection toFeatureCollection(List<WaterBody> bodies) {
        ListFeatureCollection collection = new ListFeatureCollection(featureType);

        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(featureType);
        for (WaterBody wb : bodies) {
            builder.set("the_geom", wb.getGeometry());
            builder.set("fid", wb.getFid());
            builder.set("name", wb.getName());
            builder.set("area_km2", wb.getAreaSqKm());
            builder.set("perim_m", wb.getPerimeter());
            builder.set("ellipt", wb.getEllipticity());
            builder.set("slender", wb.getSlenderness());
            builder.set("type", wb.getWaterType().name());
            builder.set("level", wb.getHierarchyLevel());

            String id = String.format("water.%d", wb.getFid());
            collection.add(builder.buildFeature(id));
        }

        return collection;
    }

    /**
     * 定义输出 SHP 的属性结构
     */
    private static SimpleFeatureType createFeatureType() {
        SimpleFeatureTypeBuilder tb = new SimpleFeatureTypeBuilder();
        tb.setName("WaterBody");
        tb.setCRS(ShapeFileHelper.WGS84);

        tb.add("the_geom", MultiPolygon.class);
        tb.add("fid", Long.class);
        tb.add("name", String.class);
        tb.add("area_km2", Double.class);
        tb.add("perim_m", Double.class);
        tb.add("ellipt", Double.class);
        tb.add("slender", Double.class);
        tb.add("type", String.class);
        tb.add("level", Integer.class);

        return tb.buildFeatureType();
    }
}
