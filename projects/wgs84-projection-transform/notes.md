# 实验过程记录 (Notes)

## 记录日期: 2026-05-26

### 实验日志
- [x] 初始化项目结构和 Maven 配置。
- [x] 编写核心转换代码。
- [x] 测试 3411/3412/3857 的转换结果。
- [x] 批量转换 data/ 下的 GeoJSON 文件（点、线、面）。

### 调试记录
- 现象: 转换 `firUirFull_R.json` 到 `EPSG:3857` 时报错 `Latitude 90°00.0'S is too close to a pole`。
- 原因: Web Mercator (EPSG:3857) 投影范围有限，无法处理极点坐标。
- 解决: 在转换前增加了纬度截断（`clampLatitude`，限制到 ±85.06°），确保超出范围的数据也能正常输出而非直接跳过。

### 性能数据
| 指标 | 数值 | 备注 |
|:---|:---|:---|
| 批量转换 (3文件 x 3坐标系) | 约 5-10s | 包含文件 IO 和 GeoTools 初始化 |
| 单文件转换耗时 | < 1s | 依赖数据量大小 |

---

## 记录日期: 2026-05-29

### 实验日志
- [x] 新增经度标准化处理（`normalizeLongitude`），将超出 [-180, 180] 范围的经度归一化。
- [x] 新增日更线（180° 经线）跨越检测与切割算法。
- [x] 新增 `antimeridian_test.json` 测试数据文件。
- [x] 编写 `crossesAntimeridian` 和 `splitAntimeridian` 的单元测试。
- [x] 配置 Logback 日志框架，解决 GeoTools 内部 JUL 日志刷屏问题。
- [x] 引入 `SLF4JBridgeHandler` 将 JUL 桥接到 SLF4J，统一日志管理。
- [x] 配置 maven-surefire-plugin 和 maven-compiler-plugin，设置编码为 UTF-8。
- [x] 完善 README 文档，包含完整代码运行流程图和技术栈说明。

### 转换预处理流水线

每个要素的几何体在进入 `JTS.transform()` 之前，依次经过以下预处理步骤：

```
原始几何体 (WGS84)
  → Step 0: normalizeLongitude()   经度标准化到 [-180, 180]
  → Step 1: crossesAntimeridian() 检测是否跨越日更线
  → Step 2: splitAntimeridian()   若跨越，沿 180° 经线切割
  → Step 3: clampLatitude()       仅 EPSG:3857，截断纬度至 ±85.06°
  → Step 4: JTS.transform()       执行实际坐标投影变换
```

### 调试记录

#### 日更线跨越问题
- 现象: 部分飞行情报区（如俄罗斯 FIR）在投影后出现横跨两极的异常长条带。
- 原因: 几何体跨越 180° 经线（如 170° ~ -170°），JTS 将经度差解释为 340° 而非 20°，导致投影错乱。
- 解决: 实现日更线检测与切割算法。将经度平移至 [0, 360) 后沿 180° 裁剪为左右两部分，右侧平移回 [-180, 0]，按原几何类型重新组装。

#### GeoTools JUL 日志刷屏
- 现象: 运行程序时控制台被 GeoTools 内部 WARNING 级别日志刷屏。
- 原因: GeoTools 内部使用 `java.util.logging` (JUL)，不受 SLF4J/Logback 管理。
- 解决: 两重处理 — (1) `SLF4JBridgeHandler` 将 JUL 日志桥接到 SLF4J；(2) Logback 配置中设置 `org.geotools` 日志级别为 ERROR。

#### 经纬度轴序
- 现象: 部分教程中 GeoTools 输出为 (纬度, 经度) 顺序，与 GeoJSON 规范不一致。
- 原因: GeoTools 默认遵循 CRS 定义中的轴序（EPSG:4326 为 lat/lon）。
- 解决: 设置 `org.geotools.referencing.forceXY=true`，强制使用 (经度, 纬度) 轴序。

### 新增测试覆盖

| 测试方法 | 覆盖内容 |
|:---|:---|
| `testCrossesAntimeridian` | 普通矩形不跨越 / 跨日更线线段检测 |
| `testSplitAntimeridian` | 跨日更线 Polygon → 切割为 MultiPolygon（2段） |

### 配置要点

- **Logback**: 控制台输出 UTF-8 编码，`org.geotools` 日志级别设为 ERROR 抑制冗余输出
- **Maven**: `maven-compiler-plugin` 强制 Java 17，`exec-maven-plugin` 预设 UTF-8 编码
- **JVM**: `forceXY=true` 确保经纬度轴序符合直觉

---

## 记录日期: 2026-05-29（后续）

### 重构与调试

- [x] 抽取 `GeometryUtils`、`GeoJSONTransformer`，App.java 仅保留入口
- [x] `GeometryUtilsTest` 新增 30 个单元测试
- [x] 修复 VSCode Debug 工作目录不一致问题
- [x] 修复输出路径与项目路径不同源的问题
- [x] 修复 Windows 路径解析问题

### 调试记录

#### VSCode Debug 工作目录不一致
- 现象: Debug 时进入 `if (inputFiles == null || inputFiles.length == 0)` 分支，提示找不到 data 文件夹；终端 `mvn exec:java` 正常。
- 原因: 终端运行时 `user.dir` = 项目目录（`projects/wgs84-projection-transform`），Debug 时 `user.dir` = workspace 根目录（`D:\Work\JavaLabs`），`new File("data")` 相对路径指向了错误位置。
- 尝试方案1: 向上遍历找 `pom.xml` → 失败，因为 `pom.xml` 在子目录而非上级。
- 尝试方案2: 扫描一级子目录找 `data/` → 用户指出多项目场景下可能定位到别的项目。
- 最终方案: 使用 `App.class.getProtectionDomain().getCodeSource().getLocation().toURI()` 获取类文件路径（`target/classes/...`），再向上遍历找 `pom.xml`，确保定位到本项目根目录。

#### 输出路径与项目路径不同源
- 现象: 转换 `firUirFull_R.json` 到 `EPSG:3857` 报错 `系统找不到指定的路径`。
- 原因: `App.java` 在 `projectDir.resolve("output")` 创建输出目录，但 `GeoJSONTransformer.transform()` 内部用 `new File("output", ...)` 写文件，路径相对于 CWD。两处路径指向不同目录。
- 解决: `transform()` 方法新增 `File outputDir` 参数，由 App 传入已解析的绝对路径。

#### `System.exit(0)` 导致控制台关闭
- 现象: Debug 时发生异常，catch 日志刚打出，控制台立刻消失，无法查看错误信息。
- 原因: `main()` 末尾的 `System.exit(0)` 直接杀死 JVM 进程，Debug 控制台随之关闭。
- 解决: 去掉 `System.exit(0)`，让 `main()` 自然结束。

#### Windows 路径前导斜杠问题
- 现象: `App.class.getProtectionDomain().getCodeSource().getLocation().getPath()` 在 Windows 返回 `/D:/Work/JavaLabs/...`（带前导斜杠），`Paths.get()` 无法正确解析并抛出异常，被 catch 吞掉后 fallback 到 `user.dir`。
- 解决: 改用 `.toURI()` 而非 `.getPath()`，URI 格式在 Windows 下能正确解析。

#### Maven 运行目录错误
- 现象: 在 `D:\Work\JavaLabs` 下运行 `mvn compile exec:java` 报错 `There is no POM in this directory`。
- 原因: Maven 需要在包含 `pom.xml` 的项目目录下执行。
- 解决: `cd projects/wgs84-projection-transform` 后再运行，或用 `mvn -f projects/wgs84-projection-transform/pom.xml ...`。

### 配置要点

- **路径定位**: 优先使用 `user.dir`（终端），回退使用类文件路径向上找 `pom.xml`（Debug），兼容两种场景
- **输出目录**: 用参数传递绝对路径，避免相对路径的 CWD 依赖
