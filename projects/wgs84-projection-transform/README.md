# WGS84 坐标系转换实验

# 项目目的

验证 WGS84 (EPSG:4326) 到 EPSG:3411 (NSIDC Sea Ice Polar Stereographic North)、EPSG:3412 (NSIDC Sea Ice Polar Stereographic South) 和 EPSG:3857 (Web Mercator) 的转换准确性与性能。支持批量转换 GeoJSON 格式的点、线、面数据。

# 输入

- 经纬度坐标 (WGS84)
- `data/` 目录下的 GeoJSON 文件：
    - `airports_P.json` (点)
    - `basicAirline_A_High_L.json` (线)
    - `firUirFull_R.json` (面)
- 目标 EPSG 代码: 3411, 3412, 3857

# 输出

- `output/` 目录下转换后的 GeoJSON 文件
- 转换后的平面坐标 (X, Y)
- 转换耗时统计

# 实验步骤

1. 初始化 GeoTools 引用库，添加 `gt-geojson` 依赖。
2. 扫描 `data/` 目录下的所有 GeoJSON 文件。
3. 针对每个文件，分别转换为 3411、3412、3857 坐标系。
4. 处理面数据时，针对极点坐标的投影限制进行容错处理。
5. 记录转换结果和性能数据。

# 结论

- **EPSG:3857**: 转换正常，适用于 Web 地图。但无法处理包含极点 (Latitude 90°) 的数据。
- **EPSG:3411/3412**: 极地投影转换需要启用“宽容模式 (lenient)”，能够完美处理极地附近的点线面数据。
- **批量处理**: GeoTools 的 `FeatureJSON` 和 `JTS.transform` 组合可以高效完成批量矢量数据的投影转换。

# 坑点

- GeoTools 的 `gt-epsg-hsql` 依赖较大，初次加载较慢。
- 极地投影 (3411/3412) 在接近赤道时可能存在巨大畸变。
- **Bursa wolf parameters required**: 某些投影（如 3411）在进行坐标转换时，若 GeoTools 无法找到精确的基准面转换参数会报错。解决方法是在 `CRS.findMathTransform` 时设置 `lenient = true`。
- **PowerShell 传参问题**: 运行 Maven 命令时，`-D` 参数若包含点号，需整体加引号。
- **环境要求**: 本项目已升级回 **Java 17**，请确保 `JAVA_HOME` 指向 JDK 17。
- **乱码解决**: 如果控制台输出乱码，请先执行 `chcp 65001` 切换终端编码。
- **Web Mercator 限制**: EPSG:3857 无法处理纬度为 90° 或非常接近 90° 的数据，会导致转换异常。

# 可复用内容

- GeoTools 批量 GeoJSON 转换逻辑。
- `GeoTransformUtil` (已沉淀到 shared 库)。

# 后续优化

- 引入并行流加速超大 GeoJSON 文件的转换。
