package com.fileark.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

/**
 * 启动后自动用系统默认浏览器打开控制台（app.auto-open-browser=false 可关闭）。
 * 无桌面环境（headless 服务器）只打印访问地址，不报错。
 */
@Component
@Order(2) // 在 RecoveryRunner(默认顺序) 之后执行
public class BrowserLauncher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    @Value("${server.port:8080}")
    private int port;

    private final AppProperties props;

    public BrowserLauncher(AppProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String url = "http://localhost:" + port;
        if (!Boolean.TRUE.equals(props.autoOpenBrowser())) {
            log.info("控制台地址: {}", url);
            return;
        }
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
            log.info("无桌面环境，请在浏览器打开: {}", url);
            return;
        }
        try {
            Desktop.getDesktop().browse(new URI(url));
            log.info("已在默认浏览器打开 {}", url);
        } catch (Exception e) {
            log.info("自动打开浏览器失败（不影响服务），请手动访问: {}", url);
        }
    }
}
