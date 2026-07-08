# Water Body Classifier — 世界水体分类与分级处理

## 项目目的

读取世界水系 SHP 数据，对每个水体要素：
1. **计算形态指标** — 周长、面积、椭圆度、狭长度
2. **判定水体类型** — 湖泊（Lake）、河流（River）、大洋（Ocean）
3. **划分层级** — 按面积分 4 级
4. **输出分级 SHP** — 每个层级生成独立的 Shapefile

## 输入

- **数据来源**: OpenStreetMap / Natural Earth 世界水系 SHP 面要素
- **坐标系**: WGS84 (EPSG:4326)
- **格式**: Shapefile (.shp)，面要素 (MultiPolygon / Polygon)

## 输出

`output/` 目录下生成按层级划分的 SHP 文件：

| 文件 | 层级 | 面积范围 |
|:---|:---|:---|
| `World_Water_Level-1-Ocean.shp` | 1 — 大洋级 | ≥ 1,000,000 km² |
| `World_Water_Level-2-Large.shp` | 2 — 大型 | ≥ 10,000 km² |
| `World_Water_Level-3-Medium.shp` | 3 — 中型 | ≥ 100 km² |
| `World_Water_Level-4-Small.shp` | 4 — 小型 | < 100 km² |

每个要素在 SHP 属性表中保留：`area_km2`, `perim_m`, `ellipt`, `slender`, `type`, `level`。

## 核心流程

```
输入 SHP → ShapeFileReader → WaterBodyClassifier → ShapeFileWriter → 分级 SHP
                │                        │
                └── 加载多边形            ├── 计算面积/周长
                                          ├── 椭圆度/狭长度
                                          ├── 判定类型
                                          └── 划分层级
```

## 运行方式

```bash
cd water-body-classifier

# 将世界水系 SHP 放入 data/input/ 并修改 App.java 中的 INPUT_SHP 路径
mvn compile exec:java
```

## 类结构

```
com.lab.water
├── App.java                          — 入口
├── model/
│   ├── WaterBody.java                — 水体要素模型
│   └── WaterBodyType.java            — 水体类型枚举
├── classify/
│   ├── WaterBodyClassifier.java      — 分类器（形态计算 + 类型判定）
│   └── ClassificationConfig.java     — 阈值配置
└── io/
    ├── ShapeFileReader.java          — SHP 读取器
    ├── ShapeFileWriter.java          — SHP 写入器
    └── ShapeFileHelper.java          — GeoTools DataStore 工具
```

## 分类算法

| 指标 | 公式 | 说明 |
|:---|:---|:---|
| **面积** | `geom.getArea()` | m²，转换为 km² |
| **周长** | `geom.getLength()` | m |
| **椭圆度** | `π·(a/2)² / S` | a=长轴, S=面积；越接近 1 越圆 |
| **狭长度** | `a / b` | a=长轴, b=短轴；越大越狭长 |

- 面积 ≥ 1,000,000 km² → **OCEAN**
- 狭长度 ≥ 5 且 椭圆度 ≤ 0.3 → **RIVER**
- 其余 → **LAKE**

## 坑点

- 待总结

## 后续优化

- 实现投影转换后再计算面积（等面积投影）
- 支持 GlobCover / ESA CCI 等栅格数据辅助分类
- 河流采用缓冲区 + 长宽比优化识别
- 多线程并行处理
