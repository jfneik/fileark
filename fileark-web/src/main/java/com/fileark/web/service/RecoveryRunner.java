package com.fileark.web.service;

import com.fileark.core.execute.RecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动即执行崩溃恢复：EXECUTING 状态的计划按撤销日志重放回滚。 */
@Component
public class RecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryRunner.class);

    private final RecoveryService recoveryService;

    public RecoveryRunner(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = recoveryService.recoverOnStartup();
        if (recovered > 0) {
            log.warn("检测到上次执行中断，已按撤销日志恢复 {} 个计划；NEEDS_REVIEW 计划请到执行中心人工确认", recovered);
        }
    }
}
