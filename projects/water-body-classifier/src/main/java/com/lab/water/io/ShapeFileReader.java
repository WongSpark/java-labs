package com.lab.water.io;

import com.lab.water.model.WaterBody;
import org.geotools.api.data.DataStore;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.store.ContentFeatureSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SHP 文件读取器 — 从 Shapefile 加载水体要素。
 */
public class ShapeFileReader {

    private static final Logger log = LoggerFactory.getLogger(ShapeFileReader.class);

    /**
     * 从 SHP 文件读取所有水体要素（含几何 + 属性）。
     */
    public List<WaterBody> read(File shpFile) throws IOException {
        List<WaterBody> bodies = new ArrayList<>();

        ShapeFileHelper.readDataStore(shpFile, store -> {
            String typeName;
            try {
                typeName = store.getTypeNames()[0];
            } catch (IOException e) {
                throw new RuntimeException("获取 SHP 类型名失败", e);
            }
            try {
                ContentFeatureSource source = (ContentFeatureSource) store.getFeatureSource(typeName);
                try (SimpleFeatureIterator it = source.getFeatures().features()) {
                    long fid = 0;
                    while (it.hasNext()) {
                        SimpleFeature feature = it.next();
                        WaterBody body = featureToWaterBody(feature, fid++);
                        if (body != null) {
                            bodies.add(body);
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("读取 SHP 要素失败", e);
            }
            return null; // void
        });

        log.info("读取 SHP [{}] 完成，共 {} 个要素", shpFile.getName(), bodies.size());
        return bodies;
    }

    /**
     * 将 GeoTools SimpleFeature 转换为 WaterBody 模型
     */
    private WaterBody featureToWaterBody(SimpleFeature feature, long fid) {
        Object geom = feature.getDefaultGeometry();
        if (!(geom instanceof org.locationtech.jts.geom.MultiPolygon mp)) {
            return null; // 仅处理面要素
        }

        String name = feature.getAttribute("name") != null
                ? feature.getAttribute("name").toString()
                : null;

        return WaterBody.builder()
                .fid(fid)
                .name(name)
                .geometry(mp)
                .build();
    }
}
