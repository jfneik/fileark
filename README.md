# FileArk 文件方舟

本地文件智能整理与去重引擎：扫描目录 → 两阶段指纹查重 → 规则自动归档 → **事务式执行（预检 + 撤销日志 + 崩溃恢复）** → 安全区兜底。所有危险操作可预览、可撤销。


## 模块结构

```
fileark/
├── fileark-core/     # 领域逻辑：指纹、查重、规则引擎、事务执行器、安全区、dHash、扫描编排
│                     #   （零 Spring 依赖，由 ArchUnit 在 CI 中强制约束）
├── fileark-storage/  # SQLite 持久化 + 内置版本化迁移器（实现 core 的端口接口）
├── fileark-web/      # Spring Boot 3.5：REST API + SSE 进度推送 + 启动恢复
├── fileark-ui/       # Vue 3 + Element Plus 控制台（构建产物打进 web 的 jar）
└── docs/             # 设计文档与 ADR
```

依赖方向：`web → storage → core`。core 通过 `port/` 下的接口反转对存储的依赖。

## 快速开始

前置：JDK 21、Maven 3.9、Node.js ≥ 20。

```bash
# 1. 生成 Maven Wrapper（首次，之后可用 ./mvnw）
mvn wrapper:wrapper

# 2. 构建前端（产物输出到 fileark-web/src/main/resources/static）
cd fileark-ui && npm install && npm run build && cd ..

# 3. 后端测试与打包
./mvnw verify          # 或 mvn verify

# 4. 启动（本地服务，无任何外部依赖）
mvn -pl fileark-web spring-boot:run
# 浏览器打开 http://localhost:8080 （API 文档: /swagger-ui.html）
```

日常开发可前后端分离：后端照常启动，前端 `npm run dev`（Vite 代理 `/api` 到 8080）。


1. **两阶段指纹查重**：size 分桶 → 头/中/尾 4KB 抽样哈希出「疑似组」→ 后台虚拟线程并行全量 SHA-256 确认。疑似结果秒级可见，误报在确认阶段剔除。
2. **自实现文件事务**：文件系统不支持事务，用「执行前预检 + 变更前写撤销日志 + 计划状态机（PLANNED/EXECUTING/EXECUTED/FAILED/ROLLED_BACK/NEEDS_REVIEW）+ 启动重放」实现可撤销、可崩溃恢复的批量文件操作。
3. **虚拟线程并发**：全量哈希等 IO 密集任务跑在 `Executors.newVirtualThreadPerTaskExecutor()` 上，`Semaphore` 限制文件句柄并发。
4. **手写感知哈希**：dHash（灰度 → 9×8 降采样 → 相邻比较 → 64-bit）+ 汉明距离 + 并查集传递闭包分组。
5. **架构边界**：core 零 Spring 依赖；dry-run 与真实执行共用同一计划构建路径（属性测试保证预览即所得）。

## 测试

- 单元：哈希已知向量、规则真值表、路径模板（含目录穿越拒绝）
- 故障注入：第 k 条操作失败 → 自动逆序回滚；模拟崩溃 → 重启恢复
- **jqwik 属性测试**：不变量 A（任意操作序列执行+撤销后目录树逐字节还原）、不变量 B（构建计划绝不触碰磁盘）
- ArchUnit：core 不依赖 Spring/外层模块，storage 不依赖 web
- 存储层 SQLite 往返冒烟测试

```bash
mvn -pl fileark-core test        # 仅领域层
mvn verify                        # 全部 + ArchUnit + 覆盖率
```

## 与设计文档的实现偏差

| 偏差 | 原因 |
|---|---|
| Flyway → 内置版本化迁移器（`SchemaMigrator` + `db/migration/index.txt`） | SQLite 属 Flyway 10+ 的社区数据库支持，官方 artifact 不稳定；内置实现约 100 行且行为可控 |
| API 新增 `POST /api/plans`（持久化计划） | 文档原只有 dry-run 与 execute；执行需要先持久化计划以获得 id |
| 测试未引入 Mockito | 实际未用到 mock，仓储有内存桩（`InMemoryStores`），引入未用依赖反而失真 |
| `mvnw` 未随仓库提交 | 环境约束（本阶段不执行构建命令）；`mvn wrapper:wrapper` 一条命令即可生成 |

## 当前状态

- [x] M0 骨架 + M1 扫描查重 + M2 规则与事务 + M3 前端五页面（代码完成，待首次构建验证）
- [ ] M4 目录监控（`watch/` 包设计稿在文档 §4.6，代码留待该里程碑）
- [ ] M5 仪表盘（F8）、jpackage 桌面打包（F9）

> 注意：本项目在编写阶段未执行任何构建/运行命令，首次 `mvn verify` 可能需要微调依赖版本号（pom 中版本以编写时公开版本为准）。
