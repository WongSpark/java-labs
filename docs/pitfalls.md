# 踩坑记录 (Pitfalls)

记录在 Java 实验过程中遇到的通用问题及其解决方案。

## 通用坑点

| 问题描述 | 影响范围 | 解决方案 | 关联项目 |
|:---|:---|:---|:---|
| PowerShell 下 Maven `-D` 参数包含点号时解析错误 | 命令行运行 | 将参数用双引号包裹，如 `"-Dexec.mainClass=..."` | 通用 |
| JDK 版本不匹配 (Environment vs POM) | 编译阶段 | 优先升级环境至 JDK 17。若环境受限，需降级 POM 版本及依赖版本 (如 GeoTools 降级至 24.0 以支持 JDK 8) | 通用 |
| GeoTools 报错 `Bursa wolf parameters required` | 坐标转换 | 在 `CRS.findMathTransform(source, target, true)` 中启用宽容模式 | GIS 项目 |
| Maven `exec:java` 报 `IllegalThreadStateException` | 运行结束阶段 | GeoTools 内部有守护线程未及时关闭。**最佳实践**：在 `main` 结尾直接显式调用 `System.exit(0)` 强制退出。 | 通用 |
| 控制台输出中文乱码 | 运行阶段 | Windows 终端执行 `chcp 65001` 切换代码页，并在运行时增加参数 `"-Dfile.encoding=UTF-8"`。 | 通用 |
| 第三方库不在 Maven Central | 依赖解析 | 在 `pom.xml` 中配置 `<repositories>` 添加专属仓库。如 UCAR CDM 需配 `https://artifacts.unidata.ucar.edu/repository/unidata-all/`。配置后注意检查仓库 URL 是否仍有效，部分老旧仓库可能已迁移。 | 通用 |
| Maven systemProperties 在 IDE 调试时不生效 | 运行阶段 | `exec-maven-plugin` 的 `<systemProperties>` 只在 Maven 启动的 JVM 子进程中生效。IDE Debug 直接启动 `main()` 时不会读取这些属性，需在 IDE 的 Run Configuration 或 `launch.json` 中手动添加 `vmArgs`（如 `"-Dapp.datadir=..."`）。同时在代码中为 `System.getProperty()` 提供合理的 fallback 默认值。 | 通用 |
