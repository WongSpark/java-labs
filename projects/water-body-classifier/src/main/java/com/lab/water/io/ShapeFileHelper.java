package com.lab.water.io;

import org.geotools.api.data.DataStore;
import org.geotools.api.data.DataStoreFinder;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.MultiPolygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * SHP 文件 I/O 工具类 — 封装 GeoTools DataStore 读写细节。
 *
 * <p>注意: GeoTools 30.x 的 DataStore 不实现 {@link AutoCloseable}，
 * 必须手动调用 {@link DataStore#dispose()} 释放资源。</p>
 */
public final class ShapeFileHelper {

    private static final Logger log = LoggerFactory.getLogger(ShapeFileHelper.class);

    /** WGS84 坐标参考系 */
    public static final CoordinateReferenceSystem WGS84;

    static {
        try {
            WGS84 = CRS.decode("EPSG:4326");
        } catch (FactoryException e) {
            throw new RuntimeException("无法初始化 WGS84 CRS", e);
        }
    }

    private ShapeFileHelper() {}

    // ========== 读取 ==========

    /**
     * 使用 DataStore 读取 SHP，操作完成后自动 dispose。
     *
     * @param shpFile SHP 文件
     * @param handler 处理 DataStore 的回调（只读操作）
     */
    public static <T> T readDataStore(File shpFile, Function<DataStore, T> handler) throws IOException {
        DataStore store = openDataStore(shpFile);
        try {
            return handler.apply(store);
        } finally {
            store.dispose();
        }
    }

    /**
     * 只读打开 DataStore。
     */
    private static DataStore openDataStore(File shpFile) throws IOException {
        Map<String, Object> params = connectionParams(shpFile);
        DataStore store = DataStoreFinder.getDataStore(params);
        if (store == null) {
            throw new IOException("无法打开 SHP: " + shpFile);
        }
        return store;
    }

    // ========== 写入 ==========

    /**
     * 将 FeatureCollection 写入 SHP 文件。如果文件已存在则覆盖。
     */
    public static void writeFeatureCollection(SimpleFeatureCollection collection, File shpFile)
            throws IOException {

        // 确保父目录存在
        File parent = shpFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // 删除已有文件
        deleteShapeFile(shpFile);

        Map<String, Object> params = connectionParams(shpFile);
        DataStore store = DataStoreFinder.getDataStore(params);
        if (store == null) {
            throw new IOException("无法创建 SHP DataStore: " + shpFile);
        }
        try {
            String typeName = store.getTypeNames()[0];
            SimpleFeatureStore featureStore = (SimpleFeatureStore) store.getFeatureSource(typeName);
            featureStore.addFeatures(collection);
        } finally {
            store.dispose();
        }
    }

    // ========== 内部方法 ==========

    private static Map<String, Object> connectionParams(File shpFile) {
        Map<String, Object> params = new HashMap<>();
        params.put("url", shpFile.toURI().toString());
        return params;
    }

    private static void deleteShapeFile(File shpFile) {
        // 删除 SHP 及配套文件
        String base = shpFile.getAbsolutePath();
        base = base.substring(0, base.lastIndexOf('.'));
        for (String ext : new String[]{".shp", ".shx", ".dbf", ".prj", ".cpg"}) {
            File f = new File(base + ext);
            if (f.exists()) f.delete();
        }
    }
}
