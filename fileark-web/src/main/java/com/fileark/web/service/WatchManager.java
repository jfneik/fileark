package com.fileark.web.service;

import com.fileark.core.execute.TransactionalExecutor;
import com.fileark.core.model.FileMeta;
import com.fileark.core.plan.PlanStatus;
import com.fileark.core.plan.Planner;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.RuleStore;
import com.fileark.core.port.SettingsStore;
import com.fileark.core.rule.Rule;
import com.fileark.core.rule.RuleEngine;
import com.fileark.core.watch.DirectoryWatcher;
import com.fileark.core.watch.FileStabilityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目录监控管理（F6）：绑定目录 → 新文件稳定后自动套用规则 →
 * 生成「自动计划」并立即执行（与手动计划同一事务路径，同样可撤销）。
 * 绑定关系持久化在 SettingsStore（key=watch.dirs），重启自动恢复。
 */
@Component
public class WatchManager {

    public static final String SETTINGS_KEY = "watch.dirs";
    private static final Logger log = LoggerFactory.getLogger(WatchManager.class);

    private final RuleStore ruleStore;
    private final RuleEngine ruleEngine;
    private final Planner planner;
    private final PlanStore planStore;
    private final TransactionalExecutor executor;
    private final SettingsStore settings;
    private final Map<String, DirectoryWatcher> active = new ConcurrentHashMap<>();

    public WatchManager(RuleStore ruleStore, RuleEngine ruleEngine, Planner planner,
                        PlanStore planStore, TransactionalExecutor executor, SettingsStore settings) {
        this.ruleStore = ruleStore;
        this.ruleEngine = ruleEngine;
        this.planner = planner;
        this.planStore = planStore;
        this.executor = executor;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreBindings() {
        String saved = settings.get(SETTINGS_KEY).orElse("");
        for (String dir : saved.split(",")) {
            if (!dir.isBlank() && java.nio.file.Files.isDirectory(Path.of(dir))) {
                startInternal(Path.of(dir));
                log.info("已恢复目录监控: {}", dir);
            }
        }
    }

    public synchronized void start(Path dir) throws IOException {
        if (!java.nio.file.Files.isDirectory(dir)) {
            throw new IllegalArgumentException("监控目录不存在或不是目录: " + dir);
        }
        if (active.containsKey(dir.toString())) {
            return;
        }
        startInternal(dir);
        persist();
    }

    public synchronized void stop(Path dir) {
        DirectoryWatcher watcher = active.remove(dir.toString());
        if (watcher != null) {
            watcher.close();
        }
        persist();
    }

    public List<String> list() {
        return new ArrayList<>(active.keySet());
    }

    private void startInternal(Path dir) {
        try {
            DirectoryWatcher watcher = new DirectoryWatcher(dir, 500,
                    this::handleNewFile, new FileStabilityChecker());
            watcher.start();
            active.put(dir.toString(), watcher);
        } catch (IOException e) {
            throw new IllegalStateException("无法监控目录: " + dir, e);
        }
    }

    /** 监控线程串行调用：新文件 → 规则命中 → 自动计划 → 事务执行。 */
    private void handleNewFile(Path file) {
        try {
            FileMeta meta = FileMeta.of(file);
            List<Rule> rules = ruleStore.all();
            if (ruleEngine.firstMatch(meta, rules).isEmpty()) {
                return;
            }
            Path baseDir = Path.of(file.getParent().toString());
            long planId = planStore.create(PlanStatus.PLANNED,
                    "自动整理（监控）: " + file.getFileName(),
                    planOps(meta, rules, baseDir));
            PlanStatus result = executor.execute(planId).status();
            log.info("监控自动整理完成: {} → 计划 #{} {}", file, planId, result);
        } catch (Exception e) {
            log.warn("监控自动整理失败: {} ({})", file, e.getMessage());
        }
    }

    private List<com.fileark.core.plan.Operation> planOps(FileMeta meta, List<Rule> rules, Path baseDir) {
        com.fileark.core.plan.Plan draft = planner.fromRules(List.of(meta), rules, baseDir, "auto");
        return draft.operations();
    }

    private void persist() {
        settings.put(SETTINGS_KEY, String.join(",", active.keySet()));
    }

    /** 供 UI 提示：某目录当前是否有启用的规则（无规则时监控不会产生任何动作）。 */
    public boolean hasEnabledRules() {
        Optional<Rule> any = ruleStore.all().stream().filter(Rule::enabled).findFirst();
        return any.isPresent();
    }
}
