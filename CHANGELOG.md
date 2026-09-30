# 更新日志（Changelog）

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.1.0] - 2026-09-29

### 新增
- **一键清理全部重复组**（`DUPLICATE_CLEANUP_ALL`）：一次扫描的全部确认组合并为一个计划，一次预检一次执行，可整体撤销
- **CSV 报告导出**：`GET /api/scans/{id}/duplicates/export`，Excel/WPS 可直接打开，用于留档与人工核对
- **受保护目录**：设置 `scan.excludes`（逗号分隔目录名）扩展扫描排除范围，被排除目录永不进入索引
- **相似图片页面**：dHash 检测结果缩略图网格展示（点击放大、阈值可调）
- **缩略图接口**：`GET /api/files/{id}/thumbnail`（只接受已索引档案 id）
- **人工复核闭环**：`NEEDS_REVIEW` → 标记已复核 → `ACKNOWLEDGED` 终态
- **扫描根目录防呆**：拒绝扫描磁盘根目录与整个用户主目录
- **一键启停脚本**：`scripts/start.sh` / `scripts/stop.sh`
- **启动自动打开浏览器**（headless 自动降级，`app.auto-open-browser=false` 可关）
- **安全区一键清除到期**（`purge-expired`，需 `confirm=true`）
- **GitHub Actions CI**：后端 `mvnw verify` + 前端构建
- 扫描输入支持 `~/Downloads` 写法；页面时间本地化展示；扫描历史一键重扫

### 修复
- 启动恢复补全：上次中断的 RUNNING 扫描现在会被标记 FAILED（此前永远显示"扫描中"）
- 监控目录停止时误伤 settings 中子串相同的其他目录（字符串 replace 逻辑移除）
- 自定义父 POM 缺少 `-parameters` 编译参数导致 Spring 6.1 `@PathVariable` 全部 400
- 深色模式系统下页面画布发黑、标题不可读（未声明背景色）
- 事务执行器安全区目标路径双重生成 UUID 导致撤销日志与实际去向不一致（P0）
- 回滚顺序违反 LIFO 导致新建空目录残留（属性测试发现）
- 执行器移入安全区未登记 `quarantine_item`，安全区页面永远为空
- 监控器启动竞态（异步注册丢事件）与新建子目录不补扫

### 安全
- 服务默认只监听 `127.0.0.1`（此前绑定全部网卡，局域网可访问）

## [1.0.0] - 2026-09-29

### 新增
- 首个完整版本：两阶段指纹查重（size 分桶 → 抽样哈希 → 虚拟线程并行全量 SHA-256）
- 自实现文件事务：预检 + 撤销日志 + 六状态计划状态机 + 崩溃恢复
- 规则引擎（优先级首条命中）与路径模板（防目录穿越）
- 安全区（uuid 改名、30 天保留期、可恢复）
- 目录实时监控（WatchService + 防抖 + 文件稳定性检查，绑定持久化）
- 手写 dHash 感知哈希 + 并查集相似图分组
- SQLite 单文件存储 + 内置版本化迁移器；Spring Boot REST/SSE 后端；Vue 3 + Element Plus 控制台
- 测试体系：单元 / jqwik 属性测试 / 故障注入 / ArchUnit 架构守护 / E2E 基准脚本
