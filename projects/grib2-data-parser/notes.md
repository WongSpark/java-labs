# 实验过程记录 (Notes)

## 记录日期: 2026-06-03

### 实验日志
- [x] 初始化项目结构和 Maven 配置。
- [x] 集成 UCAR CDM (netCDF-Java) 5.9.0 作为 GRIB2 解析引擎。
- [x] 配置 Unidata 专属 Maven 仓库（`https://artifacts.unidata.ucar.edu/repository/unidata-all/`）。
- [x] 实现 GRIB2 文件读取、变量元数据提取、统计值计算。
- [x] 实现统计表多格式输出（控制台表格 / CSV / JSON）。
- [x] 配置 exec-maven-plugin 以便用 `mvn exec:java` 直接运行。
- [x] 配置 Logback + SLF4J + JUL 桥接，统一日志管理。

### Maven exec-maven-plugin 配置解读

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.1.0</version>
    <configuration>
        <mainClass>com.lab.App</mainClass>
        <workingDirectory>${project.basedir}</workingDirectory>
        <systemProperties>
            <systemProperty>
                <key>file.encoding</key>
                <value>UTF-8</value>
            </systemProperty>
            <systemProperty>
                <key>app.datadir</key>
                <value>${project.basedir}/data</value>
            </systemProperty>
            <systemProperty>
                <key>app.outdir</key>
                <value>${project.basedir}/output</value>
            </systemProperty>
        </systemProperties>
    </configuration>
</plugin>
```

#### 各配置项含义

| 配置项 | 值 | 说明 |
|:---|:---|:---|
| `groupId` / `artifactId` / `version` | `org.codehaus.mojo` / `exec-maven-plugin` / `3.1.0` | Codehaus Mojo 组织提供的 Maven 插件，用于直接在 Maven 生命周期中运行 Java 程序 |
| `mainClass` | `com.lab.App` | 指定入口类（包含 `main` 方法），等价于 `java -cp ... com.lab.App` |
| `workingDirectory` | `${project.basedir}` | JVM 进程的工作目录。`${project.basedir}` 是 Maven 内置变量，指向 `pom.xml` 所在目录。确保 `System.getProperty("user.dir")` 返回项目根目录而非当前 shell 所在目录 |
| `systemProperties` | — | 通过 JVM `-D` 参数向程序注入配置，等价于 `java -Dkey=value ...` |

#### 系统属性传递链

```
pom.xml → JVM 系统属性 → App.java 中 System.getProperty() 读取
```

| 属性 Key | 属性 Value | Java 中读取方式 |
|:---|:---|:---|
| `file.encoding` | `UTF-8` | JVM 自动识别，确保文件读写和日志输出使用 UTF-8 编码 |
| `app.datadir` | `${project.basedir}/data` | `System.getProperty("app.datadir")` 获取 GRIB2 数据文件存放目录 |
| `app.outdir` | `${project.basedir}/output` | `System.getProperty("app.outdir")` 获取统计报表输出目录 |

#### 为什么用 `systemProperties` 而非 `arguments`？

- `arguments`（`-Dexec.args="..."`）适合传递**可变**的命令行参数（如指定某个具体的文件路径）。
- `systemProperties` 适合传递**固定不变**的运行环境配置（如数据目录、输出目录），与代码解耦，不同环境只需改 pom.xml 无需改代码。

#### 运行方式

```bash
# 在项目目录下直接运行
cd projects/grib2-data-parser
mvn compile exec:java

# 或从工作区根目录指定 pom.xml 运行
mvn -f projects/grib2-data-parser/pom.xml compile exec:java

# 指定自定义数据目录（覆盖系统属性）
mvn exec:java "-Dexec.args=/path/to/grib2/files"
```

### UCAR CDM 依赖配置

#### 专属仓库

UCAR CDM 库不在 Maven Central，需配置 Unidata 专属仓库：

```xml
<repository>
    <id>unidata-all</id>
    <name>Unidata All</name>
    <url>https://artifacts.unidata.ucar.edu/repository/unidata-all/</url>
</repository>
```

#### 核心依赖与 IOSP 分离

| 依赖 | scope | 作用 |
|:---|:---|:---|
| `cdm-core` | compile | 网格数据公共 API（`GridDataset`, `GridDatatype` 等） |
| `grib` | **runtime** | GRIB1/GRIB2 的 IOSP（I/O Service Provider），实现具体的文件格式解析 |

> **关键点**: `grib` 设为 `runtime` scope，编译期不需要它，运行时由 CDM 的 SPI 机制自动发现并加载。这避免了编译期对特定格式的硬耦合。

### GRIB2 气压层识别原理

- 从 Grid 变量的 **Z 坐标轴** 读取层次值和单位
- 若单位为 `hPa` / `Pa` / `mb`，判定为**等压面 (Isobaric surface)**
- 自动换算：`Pa` 单位值 ÷ 100 → hPa
- 非等压面的层次（如 Surface、Mean sea level、Tropopause）单独归类

#### 常见气压层

模式输出中常见气压层（hPa）：1000, 975, 950, 925, 900, 850, 800, 750, 700, 650, 600, 550, 500, 450, 400, 350, 300, 250, 200, 150, 100, 70, 50, 30, 20, 10, 5, 1

### 配置要点

- **UCAR CDM**: 需要专属 Unidata Maven 仓库，不在 Maven Central
- **GRIB IOSP**: 使用 runtime scope，通过 SPI 机制自动加载
- **exec-maven-plugin**: 通过 `systemProperties` 注入 `app.datadir` / `app.outdir`，与代码解耦
- **日志**: SLF4J + Logback + `jul-to-slf4j` 桥接，统一 UCAR 库内部的 JUL 日志输出
- **Maven**: `maven-compiler-plugin` 强制 Java 17，`maven-surefire-plugin` 管理测试执行

---

## 记录日期: 2026-06-03（exec-maven-plugin 深入）

### exec-maven-plugin systemProperties 传递机制

#### 原理

`exec-maven-plugin` 在启动 JVM 子进程时，会将 `<systemProperties>` 中的每个 `<systemProperty>` 转换为 JVM 的 `-D` 参数。例如：

```
<key>app.datadir</key>
<value>${project.basedir}/data</value>
```

变为 JVM 启动参数：`-Dapp.datadir=D:\Work\JavaLabs\projects\grib2-data-parser\data`

#### 与 kotlin-maven-plugin 的区别

在 [[../wgs84-projection-transform/notes]] 中也使用了 `exec-maven-plugin`，但配置方式略有不同：

| 项目 | 配置方式 | 特点 |
|:---|:---|:---|
| grib2-data-parser | `<systemProperties>` 在 pom.xml 中硬编码 | 适合固定环境配置 |
| wgs84-projection-transform | 类似方式 + 代码内回退逻辑 | 兼容 VSCode Debug 场景 |

#### 注意事项

- `${project.basedir}` 由 Maven 在构建时解析，运行时无法修改
- 若在 VSCode Debug 中运行，这些系统属性**不会自动注入**（因为不走 exec-maven-plugin），需在 `launch.json` 中手动添加 `"vmArgs": "-Dapp.datadir=..."`
- 这也是 wgs84-projection-transform 项目中实现"回退逻辑"的原因（见 [[../wgs84-projection-transform/notes#VSCode Debug 工作目录不一致]]）

### 相关踩坑

- Maven 运行目录: 必须在 `pom.xml` 所在目录下执行 `mvn` 命令，或用 `-f` 参数指定 pom.xml 路径（见 [[../../docs/pitfalls]]）
- 控制台乱码: Windows 下 `chcp 65001` + `"-Dfile.encoding=UTF-8"` 双保险
