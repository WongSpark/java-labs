# WGS84 坐标系转换实验

## 项目目的

验证 WGS84 (EPSG:4326) 到 EPSG:3411 (NSIDC Sea Ice Polar Stereographic North)、EPSG:3412 (NSIDC Sea Ice Polar Stereographic South) 和 EPSG:3857 (Web Mercator) 的转换准确性与性能。支持批量转换 GeoJSON 格式的点、线、面数据，并处理日更线（180°经线）跨越问题。

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 运行环境 |
| GeoTools | 29.1 | 坐标转换核心引擎 |
| JTS (LocationTech) | - | 几何对象模型与空间操作 |
| Lombok | 1.18.28 | 简化日志等样板代码 |
| SLF4J + Logback | 2.0.7 / 1.4.8 | 日志框架 |
| JUnit 5 | 5.9.3 | 单元测试 |
| Maven | 3.x | 项目构建 |

核心依赖: `gt-main`, `gt-referencing`, `gt-epsg-hsql`, `gt-geojson`

## 输入

- 经纬度坐标 (WGS84 / EPSG:4326)
- `data/` 目录下的 GeoJSON 文件:
    - `firUirFull_R_R.json` — 面数据（飞行情报区，含 180° 预拆分要素）
    - `firUirFbNo180_R.json` — 面数据（全球 FIR，498 要素，无 180° 跨越）
- 目标 EPSG 代码: 3411, 3412, 3857

## 输出

- `output/` 目录下转换后的 GeoJSON 文件，命名格式: `{原文件名}_{目标EPSG}.json`
- 转换后的平面坐标 (X, Y)
- 转换过程中日更线切割、纬度截断等处理日志

## 如何运行

```bash
# 编译并运行
mvn clean compile exec:java

# 仅运行测试
mvn test

# PowerShell 中指定 JVM 参数（参数含点号需整体加引号）
mvn exec:java "-Dexec.args=..."
```

## 代码整体运行流程

```
┌──────────────────────────────────────────────────────────────┐
│                        App.main()                            │
├──────────────────────────────────────────────────────────────┤
│ 1. 环境初始化                                                │
│    ├─ 安装 SLF4J-JUL 桥接，统一日志输出                      │
│    ├─ 设置 forceXY=true（确保经纬度轴序为 X=经度, Y=纬度）   │
│    └─ 解码源坐标系 EPSG:4326 及三个目标坐标系                │
├──────────────────────────────────────────────────────────────┤
│ 2. 文件扫描                                                  │
│    └─ 扫描 data/ 目录下所有 .json / .geojson 文件            │
├──────────────────────────────────────────────────────────────┤
│ 3. 双循环处理（每个文件 × 每个目标坐标系）                   │
│    │                                                         │
│    └─ transformGeoJSON(inputFile, sourceCRS, targetCode)     │
│       │                                                      │
│       ├─ 3.1 解码目标 CRS & 获取 MathTransform               │
│       │      └─ lenient=true，容忍缺少 Bursa-Wolf 参数       │
│       ├─ 3.2 读取 GeoJSON → FeatureCollection                │
│       ├─ 3.3 构建新的 FeatureType（绑定新 CRS 和输出类型）   │
│       ├─ 3.4 遍历每个 Feature，执行几何坐标转换:              │
│       │   ├─ Step 0: normalizeLongitude()                    │
│       │   │   └─ 经度标准化到 [-180, 180]，处理超界数据      │
│       │   ├─ Step 1-3: 按目标投影分流                         │
│       │   │                                                     │
│       │   │   ┌─ EPSG:3857 ──────────────────────────────┐    │
│       │   │   │ crossesAntimeridian() → splitAntimeridian()   │
│       │   │   │ → clampLatitude(-88, 88)                     │
│       │   │   └──────────────────────────────────────────┘    │
│       │   │                                                     │
│       │   │   ┌─ EPSG:3411 ──────────────────────────────┐    │
│       │   │   │ mergeAntimeridianSplit() → clipToNorthern  │    │
│       │   │   │ Hemisphere()（纬度 >= 0）                   │
│       │   │   └──────────────────────────────────────────┘    │
│       │   │                                                     │
│       │   │   ┌─ EPSG:3412 ──────────────────────────────┐    │
│       │   │   │ mergeAntimeridianSplit() → clipToSouthern  │    │
│       │   │   │ Hemisphere()（纬度 <= 0）                   │
│       │   │   └──────────────────────────────────────────┘    │
│       │   │                                                     │
│       │   └─ Step 4: JTS.transform() 执行实际坐标变换        │
│       └─ 3.5 写入输出 GeoJSON 文件                           │
└──────────────────────────────────────────────────────────────┘
```

### 日更线（Antimeridian）处理详解

地球上经度为 180° 的线称为日更线。在 WGS84 坐标系中，跨越日更线的几何体（如俄罗斯、斐济的飞行情报区）会因经度从 +180 突变到 -180 导致渲染和投影异常。

**策略按目标投影分流**：

| 目标投影 | 处理方式 | 原因 |
|:---|:---|:---|
| **EPSG:3857** (Web Mercator) | 检测 → 切割 → 组装为多段 | 180° 是渲染边界，必须拆分 |
| **EPSG:3411** (北极立体) | 预拆分合并 → 保留完整面 | 180° 在极点汇聚，不是物理边界 |
| **EPSG:3412** (南极立体) | 预拆分合并 → 保留完整面 | 同上 |

**3857 日更线切割流程**:
1. **检测**: 遍历几何体相邻坐标点，若经度差绝对值 > 180，判定为跨越
2. **切割**: 将几何体平移至 [0, 360) 后沿 180° 一分为二，右侧平移回 [-180, 0]
3. **组装**: 切割后的多段几何体按原类型重新组装

**3411/3412 预拆分合并流程**:
1. **检测**: 识别 MultiPolygon 中负经度子面
2. **平移**: 负经度部分 x += 360，移至 [180, 360) 区间
3. **端点匹配拼接**: 去掉闭合顶点 → 开放路径，在 180° 线上匹配端点 → 拼接
4. **回退**: 拼接失败时回退到 JTS UnaryUnionOp.union()

### 半球过滤

- **EPSG:3411** (北极): 裁剪至北半球（纬度 >= 0），完全在南半球的要素跳过
- **EPSG:3412** (南极): 裁剪至南半球（纬度 <= 0），完全在北半球的要素跳过
- 仅触及半球边界（纬度=0）的要素，裁剪后为线/点等退化结果，统一过滤跳过

## 实验步骤

1. 初始化 GeoTools 引用库，添加 `gt-geojson`、`gt-epsg-hsql` 等依赖
2. 配置 Logback 日志，抑制 GeoTools 内部冗余日志（级别设为 ERROR）
3. 扫描 `data/` 目录下的所有 GeoJSON 文件
4. 针对每个文件，分别转换为 3411、3412、3857 坐标系
5. 对每个要素预处理: 经度标准化 → 按目标投影分流（3857 做日更线切割+纬度截断，3411/3412 做预拆分合并+半球过滤）
6. 处理面数据时，针对极点坐标的投影限制进行容错处理（lenient 模式）
7. 记录转换结果和运行日志

## 结论

- **EPSG:3857**: 转换正常，适用于 Web 地图。需要日更线切割 + 纬度截断至 ±88°
- **EPSG:3411/3412**: 极地投影需要启用"宽容模式 (lenient)"。180° 经线在极点汇聚，无需拆分——反而应将预拆分的 MultiPolygon 合并回单一 Polygon。需配合半球过滤剔除对侧半球数据
- **批量处理**: GeoTools 的 `FeatureJSON` 和 `JTS.transform` 组合可以高效完成批量矢量数据的投影转换
- **端点匹配拼接**: 比 JTS union 更可靠地处理 180° 预拆分面（union 因浮点精度差异无法溶解缝线）；算法简洁——去掉闭合顶点、匹配端点、拼接（必要时翻转）
- **半球裁剪**: 仅保留 Polygon/MultiPolygon 结果，过滤掉仅触及边界产生的 LineString/Point 退化几何

## 坑点

- GeoTools 的 `gt-epsg-hsql` 依赖较大（约 8MB），初次加载较慢
- 极地投影 (3411/3412) 在接近赤道时可能存在巨大畸变
- **Bursa wolf parameters required**: 某些投影（如 3411）在进行坐标转换时，若 GeoTools 无法找到精确的基准面转换参数会报错。解决方法是在 `CRS.findMathTransform` 时设置 `lenient = true`
- **PowerShell 传参问题**: 运行 Maven 命令时，`-D` 参数若包含点号，需整体加引号
- **环境要求**: 本项目使用 **Java 17**，请确保 `JAVA_HOME` 指向 JDK 17
- **乱码解决**: 如果控制台输出乱码，请先执行 `chcp 65001` 切换终端编码；Logback 配置中已设置 UTF-8 charset
- **forceXY 时序**: `System.setProperty("org.geotools.referencing.forceXY", "true")` 必须是 `main()` 中第一行可执行语句，任何 GeoTools 类加载之前。否则 EPSG:4326 默认轴序为 (lat, lon)，经纬度互换
- **Web Mercator 限制**: EPSG:3857 无法处理纬度为 ±90° 或非常接近 90° 的数据，会导致坐标发散到无穷。项目通过 `clampLatitude()` 截断到 ±88° 解决
- **GeoTools 日志**: GeoTools 内部会产生大量 JUL 级别的警告日志，通过 `SLF4JBridgeHandler` + Logback 配置统一管理
- **JTS union 精度**: 浮点精度差异（如 `87.568031` vs `87.56802850718032`）导致 union 无法溶解共享边界，端点匹配拼接是更可靠的替代方案
- **半球裁剪退化**: JTS `intersection()` 对仅触及边界的要素可能产出 LineString/Point，需额外过滤

## 可复用内容

- GeoTools 批量 GeoJSON 坐标转换逻辑（读取 → 预处理 → 变换 → 写入）
- 按目标投影分流的预处理管线（3857 / 3411 / 3412 各走独立分支）
- 日更线检测与切割算法（`crossesAntimeridian` / `splitAntimeridian`）
- 端点匹配拼接算法（`mergeAntimeridianSplit` / `stitchByEndpointMatch`）— 合并 180° 预拆分面
- 半球裁剪与退化几何过滤（`clipToNorthernHemisphere` / `clipToSouthernHemisphere`）
- 纬度截断工具方法（`clampLatitude`）
- `GeoTransformUtil`（已沉淀到 shared 库）

## 后续优化

- 引入并行流加速超大 GeoJSON 文件的转换
- 支持命令行参数指定输入目录、输出目录及目标 EPSG 代码
- 增加转换精度评估（转换前后距离偏差统计分析）
