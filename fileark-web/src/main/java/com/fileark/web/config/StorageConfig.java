package com.fileark.web.config;

import com.fileark.core.dedup.DuplicateDetector;
import com.fileark.core.execute.FileOps;
import com.fileark.core.execute.FsFileOps;
import com.fileark.core.execute.PreflightChecker;
import com.fileark.core.execute.RecoveryService;
import com.fileark.core.execute.TransactionalExecutor;
import com.fileark.core.orchestrator.ScanOrchestrator;
import com.fileark.core.plan.Planner;
import com.fileark.core.port.DuplicateStore;
import com.fileark.core.port.FileRepository;
import com.fileark.core.port.PlanStore;
import com.fileark.core.port.QuarantineStore;
import com.fileark.core.port.RuleStore;
import com.fileark.core.port.ScanStore;
import com.fileark.core.port.SettingsStore;
import com.fileark.core.port.UndoLogStore;
import com.fileark.core.quarantine.QuarantineManager;
import com.fileark.core.rule.RuleEngine;
import com.fileark.storage.Database;
import com.fileark.storage.SchemaMigrator;
import com.fileark.storage.SqliteDuplicateStore;
import com.fileark.storage.SqliteFileRepository;
import com.fileark.storage.SqlitePlanStore;
import com.fileark.storage.SqliteQuarantineStore;
import com.fileark.storage.SqliteRuleStore;
import com.fileark.storage.SqliteScanStore;
import com.fileark.storage.SqliteSettingsStore;
import com.fileark.storage.SqliteUndoLogStore;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 手工装配：web → storage → core 的所有依赖在此显式声明，便于看清模块边界。 */
@Configuration
public class StorageConfig {

    private ExecutorService hashPool;

    @Bean
    public Database database(AppProperties props) {
        Database db = new Database(AppProperties.expand(props.dbPath()));
        new SchemaMigrator(db).migrate();
        return db;
    }

    @Bean
    public ScanStore scanStore(Database db) {
        return new SqliteScanStore(db);
    }

    @Bean
    public FileRepository fileRepository(Database db) {
        return new SqliteFileRepository(db);
    }

    @Bean
    public DuplicateStore duplicateStore(Database db) {
        return new SqliteDuplicateStore(db);
    }

    @Bean
    public RuleStore ruleStore(Database db) {
        return new SqliteRuleStore(db);
    }

    @Bean
    public PlanStore planStore(Database db) {
        return new SqlitePlanStore(db);
    }

    @Bean
    public UndoLogStore undoLogStore(Database db) {
        return new SqliteUndoLogStore(db);
    }

    @Bean
    public QuarantineStore quarantineStore(Database db) {
        return new SqliteQuarantineStore(db);
    }

    @Bean
    public SettingsStore settingsStore(Database db) {
        return new SqliteSettingsStore(db);
    }

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public FileOps fileOps() {
        return new FsFileOps();
    }

    @Bean
    public QuarantineManager quarantineManager(AppProperties props, FileOps fileOps, Clock clock) {
        return new QuarantineManager(AppProperties.expand(props.quarantineDir()), fileOps,
                clock, props.retentionDays());
    }

    @Bean
    public PreflightChecker preflightChecker(FileOps fileOps) {
        return new PreflightChecker(fileOps);
    }

    @Bean
    public TransactionalExecutor transactionalExecutor(PlanStore planStore, UndoLogStore undoLogStore,
                                                       QuarantineStore quarantineStore,
                                                       FileOps fileOps, PreflightChecker preflight,
                                                       AppProperties props, Clock clock) {
        return new TransactionalExecutor(planStore, undoLogStore, quarantineStore, fileOps,
                preflight, AppProperties.expand(props.quarantineDir()), clock, props.retentionDays());
    }

    @Bean
    public RecoveryService recoveryService(PlanStore planStore, UndoLogStore undoLogStore,
                                           FileOps fileOps, TransactionalExecutor executor,
                                           ScanStore scanStore) {
        return new RecoveryService(planStore, undoLogStore, fileOps, executor, scanStore);
    }

    @Bean
    public RuleEngine ruleEngine(Clock clock) {
        return new RuleEngine(clock);
    }

    @Bean
    public Planner planner(RuleEngine ruleEngine, Clock clock) {
        return new Planner(ruleEngine, clock);
    }

    @Bean
    public DuplicateDetector duplicateDetector() {
        return new DuplicateDetector();
    }

    /** 虚拟线程池承载全量哈希等 IO 密集任务；并发由信号量在任务内部限流。 */
    @Bean
    public ExecutorService hashPool() {
        hashPool = Executors.newVirtualThreadPerTaskExecutor();
        return hashPool;
    }

    @Bean
    public ScanOrchestrator scanOrchestrator(ScanStore scanStore, FileRepository fileRepository,
                                             DuplicateStore duplicateStore,
                                             DuplicateDetector detector, ExecutorService hashPool,
                                             AppProperties props, SettingsStore settingsStore) {
        return new ScanOrchestrator(scanStore, fileRepository, duplicateStore, detector,
                hashPool, props.hashConcurrency(), settingsStore);
    }

    @PreDestroy
    public void shutdownHashPool() {
        if (hashPool != null) {
            hashPool.shutdown();
        }
    }
}
