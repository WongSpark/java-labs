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

---

## 记录日期: 2026-06-05

### 实验日志

- [x] 按目标投影分流预处理管线：3857 / 3411 / 3412 各走独立分支
- [x] 3411/3412 跳过日更线拆分（极地投影中 180° 不是物理边界）
- [x] 3411 过滤南半球数据，3412 过滤北半球数据
- [x] 实现 `mergeAntimeridianSplit`：合并数据提供方在 180° 处预拆分的 MultiPolygon
- [x] 实现端点匹配拼接算法（`stitchByEndpointMatch`），替代缝顶点识别方案
- [x] 修复 `clipToHemisphere` 退化几何问题：过滤 LineString/Point 等非面状结果
- [x] 新增 `firUirFbNo180_R.json` 测试数据（498 个全球 FIR 面要素）

### 转换预处理流水线（最终版）

```
原始几何体 (WGS84)
  → Step 0: normalizeLongitude()           经度标准化到 [-180, 180]

  ┌─ EPSG:3857 ─────────────────────────────────────────┐
  │ → Step 1: crossesAntimeridian() 检测                │
  │ → Step 2: splitAntimeridian()   日更线切割          │
  │ → Step 3: clampLatitude()       纬度截断 [-88, 88]  │
  │ → Step 4: JTS.transform()                           │
  └─────────────────────────────────────────────────────┘

  ┌─ EPSG:3411 ─────────────────────────────────────────┐
  │ → Step 1: mergeAntimeridianSplit()  合并预拆分面    │
  │ → Step 2: clipToNorthernHemisphere() 纬度 >= 0      │
  │ → Step 3: JTS.transform()                           │
  └─────────────────────────────────────────────────────┘

  ┌─ EPSG:3412 ─────────────────────────────────────────┐
  │ → Step 1: mergeAntimeridianSplit()  合并预拆分面    │
  │ → Step 2: clipToSouthernHemisphere() 纬度 <= 0      │
  │ → Step 3: JTS.transform()                           │
  └─────────────────────────────────────────────────────┘
```

### 调试记录

#### forceXY 必须位于首行
- 现象: 转换 `firUirFull_R.json` 到 EPSG:3857 报错 `Latitude 163°00.0'N is too close to a pole`。
- 原因: `System.setProperty("org.geotools.referencing.forceXY", "true")` 设置在 CRS.decode() 之后，GeoTools 已按默认轴序 (lat, lon) 初始化，经度被当作纬度处理。
- 解决: 将 `forceXY` 移至 `main()` 第一行可执行语句，在任何 GeoTools 类加载前生效。

#### 源数据 180° 预拆分问题
- 现象: UHMM（马加丹飞行情报区）在 3411 输出中仍显示 180° 分割线，类型为 MultiPolygon。
- 原因: 数据提供方已将 UHMM 沿 180° 经线预拆分为 MultiPolygon（两个子面），但极地投影中 180° 不是物理边界，拆分反而产生视觉缝隙。
- 尝试方案 1 — JTS `union()`: 失败。两个子面在 180° 缝线上存在浮点精度差异（`87.568031` vs `87.56802850718032`），union 无法溶解共享边界。
- 尝试方案 2 — 顶点拼接 V1（删除所有缝顶点）: 失败。Part 0 非缝路径最高只到 78°N，Part 1 最高到 87.6°N，产生 76.9° 缝隙。
- 尝试方案 3 — 顶点拼接 V2（保留极端纬度缝端点）: 成功但有冗余复杂度（boolean 标记、极值追踪、环形收集）。
- **最终方案 — 端点匹配拼接**: 两个环各自去掉闭合顶点 → 开放路径，在 180° 线上匹配端点，按匹配关系拼接（必要时翻转），跳过重复端点闭合。

#### 半球裁剪产生退化几何
- 现象: AYPM（莫尔兹比港 FIR，纬度 [-12, 0]）在 3411 输出中变成 LineString；GLRB 在 3412 输出中变成 Point。
- 原因: 要素仅触及半球边界（纬度=0），JTS `intersection()` 只能交出赤道上的线段或点。
- 解决: `clipToHemisphere` 返回前用 `extractPolygons` 提取面状结果，非 Polygon/MultiPolygon 的统一返回 null（该半球无有效面状覆盖）。

### 端点匹配拼接算法

```
输入: 两个 Polygon（180° 预拆分的两半）
算法:
  1. P2 负经度部分 x += 360，移至 [180, 360) 区间
  2. 去掉两个环的闭合顶点 → 开放路径 open0, open1
  3. 匹配 open0 的末端与 open1 的端点（180° 线上，容差 1e-4）:
     - e0 ≈ s1 (P1尾→P2头):   open0 + open1[1:] + [open0[0]]
     - e0 ≈ e1 (P1尾→P2尾):   open0 + reverse(open1)[1:] + [open0[0]]
     - s0 ≈ e1 (P1头→P2尾):   open1 + open0[1:] + [open1[0]]
     - s0 ≈ s1 (P1头→P2头):   reverse(open0) + open1[1:] + [reverse(open0)[0]]
  4. 匹配失败 → 回退 UnaryUnionOp.union()
  5. normalizeLongitude() 将拼接结果归一化到 [-180, 180]
```

### 新增测试覆盖

| 测试内容 | 说明 |
|:---|:---|
| `mergeAntimeridianSplit` | 通过集成测试覆盖（`firUirFull_R_R.json` 中 UHMM 要素） |
| `clipToNorthernHemisphere` | 过滤南半球 + 退化几何（LineString/Point → null） |
| `clipToSouthernHemisphere` | 过滤北半球 + 退化几何 |
| 赤道边界要素 | AYPM (lat -12→0) 在 3411 中正确跳过 |

### 配置要点

- **forceXY**: 必须是 `main()` 中第一行可执行语句，任何 GeoTools 类使用前
- **3411/3412**: 不做日更线拆分（极地投影不需要），改做预拆分合并 + 半球过滤
- **3857**: 做日更线拆分 + 纬度截断，维持原有逻辑
- **半球裁剪**: 仅保留 Polygon/MultiPolygon，过滤退化线/点
