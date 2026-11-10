# 更新日志（Changelog）

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.2.0] - 2026-10-12

### 新增
- **控制台品牌化主题**（`fileark-ui/src/styles/theme.css`）：方舟蓝品牌色整体重定义 Element Plus 色阶；吸顶毛玻璃品牌导航（渐变 Logo 标 + 安全承诺徽章）、胶囊式 Tab、卡片化面板与表格质感、仪表盘四色统计卡（图标徽章 + 悬浮上浮），告别"默认白板脸"
- **重复组保留策略可选**：扫描查重页新增「保留策略」下拉（保留最新/最旧/路径最靠前，对应后端 `KeepStrategy`），组卡片实时显示"将保留"哪个副本——修复此前单选框不生效、确认弹窗文案与实际保留不符的问题；前端 `pickKept` 与后端决胜规则（并列按路径字典序）逐位一致
- **取消进行中的扫描**：RUNNING 状态行新增「取消」按钮，接入既有 `POST /api/scans/{id}/cancel`
- **规则手动应用到目录**：规则管理新增「应用到目录」，试运行预览（dry-run）→ 创建计划 / 创建并执行，打通 `RULE_APPLY` 计划类型的 UI 入口
- **设置项真删除**：新增 `DELETE /api/settings/{key}`（`SettingsStore.remove`，SQLite 与内存测试桩同步实现），设置页「删」不再只删本地行（此前后端 upsert 合并会让键"复活"）

### 修复
- 确认框取消不再产生 unhandled rejection（统一 try/catch）；后端不可用时首屏请求有错误提示（仪表盘带重试按钮）
- SSE：连续发起扫描先关旧连接；网络抖动交给 EventSource 原生重连（浏览器彻底放弃才收尾）；组件卸载清理连接
- 切 Tab 自动刷新对应面板数据（各 Tab 暴露 `refresh`），不再看到陈旧列表；`keepChoice` 陈旧导致的"跳过确认直接执行"漏洞一并消除
- `FileEntry.mtime` 类型修正为 `string | null`（与后端 DTO 一致），排序处防空；`fmtSize` 边界（恰为 1GiB/1MiB/1KiB）进位正确
- axios 增加 30s 超时；弹窗宽度自适应小屏（`min(width, 94vw)`）；仪表盘统计卡响应式断点；README 架构图"七个页面"更正为八个

## [1.1.1] - 2026-10-07

### 性能
- **增量哈希复用 N+1 修复**：原先逐文件查库（每次新开 SQLite 连接），2 万文件二次扫描比首次慢一倍（100.7s vs 47.5s）；改为单条 SQL 全量预取（`FileRepository.allKnownHashes`，同三元组取最新）+ 内存 `path|size|mtime` 匹配，二次扫描降至 25.5s、复用率 100%
- 新增万级规模基准脚本 `scripts/bench_large.py`（20,000 文件 / 251MB：首次扫描 27s ≈ 740 文件/秒，RSS ≈ 260MB）

### 新增
- 桌面打包脚本实测通过：jpackage 产出 123MB 免安装 `.app`，独立运行验证（补齐 `java.xml/java.logging/jdk.crypto.ec` 模块；staging 只装主 jar）

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
