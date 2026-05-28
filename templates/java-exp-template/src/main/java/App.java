// 由于当前项目结构未配置正确的源文件目录，移除包声明以解决包名不匹配的编译错误

import lombok.extern.slf4j.Slf4j;

/**
 * 实验启动类
 */
@Slf4j
public class App {
    public static void main(String[] args) {
        // 初始化日志桥接
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();

        log.info("开始实验: ${project.name}");
        
        // TODO: 编写实验逻辑
        
        log.info("实验结束");
    }
}
