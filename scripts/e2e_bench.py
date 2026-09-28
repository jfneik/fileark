#!/usr/bin/env python3
"""FileArk 端到端基准与生命周期验证脚本。

前置：后端已启动（测试参数），API 位于 http://localhost:8080/api。
流程：构造合成目录 → 扫描查重 → 计划执行/撤销 → 增量扫描 → 目录监控自动整理。
输出：各阶段耗时与关键断言，全部通过则退出码为 0。
"""
import base64
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api"
BENCH = "/tmp/fileark-bench"
WATCH = "/tmp/fileark-watch"

failures = []


def check(name, ok, detail=""):
    print(f"  [{'OK' if ok else 'FAIL'}] {name}" + (f" —— {detail}" if detail else ""))
    if not ok:
        failures.append(name)


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        print(f"  ! {method} {path} -> HTTP {e.code}: {e.read().decode()[:200]}")
        raise


def make_bench_tree():
    shutil.rmtree(BENCH, ignore_errors=True)
    dup_payload = os.urandom(64 * 1024)
    n_files, t0 = 3000, time.time()
    dup_path = None
    for i in range(n_files):
        d = os.path.join(BENCH, f"d{i % 20}")
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, f"f{i}.bin")
        if i % 10 == 0 and i >= 10:
            with open(p, "wb") as f:
                f.write(dup_payload)  # 每 10 个文件一个重复副本
            if dup_path is None:
                dup_path = p
        else:
            with open(p, "wb") as f:
                f.write(os.urandom(1024 * (1 + (i * 7) % 200)))
    total = sum(os.path.getsize(os.path.join(r, f))
                for r, _, fs in os.walk(BENCH) for f in fs)
    return n_files, total, time.time() - t0


def wait_server(seconds=60):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            req("GET", "/scans")
            return
        except Exception:
            time.sleep(0.5)
    sys.exit("后端 60 秒内未就绪")


def run_scan(root):
    t0 = time.time()
    scan_id = req("POST", "/scans", {"rootPath": root})["scanId"]
    while True:
        row = req("GET", f"/scans/{scan_id}")
        if row["status"] != "RUNNING":
            break
        time.sleep(0.3)
    return scan_id, row, time.time() - t0


def main():
    n_files, total_bytes, create_t = make_bench_tree()
    print(f"合成目录：{n_files} 个文件 / {total_bytes / 1e6:.1f} MB（生成 {create_t:.1f}s）")
    wait_server()

    # 1. 首次扫描
    scan_id, row, scan_t = run_scan(BENCH)
    stats = json.loads(row["statsJson"] or "{}")
    check("首次扫描完成", row["status"] == "DONE",
          f"{n_files} 文件 {total_bytes / 1e6:.0f}MB 用时 {scan_t:.2f}s")
    print(f"  统计: {stats}")

    # 2. 重复组：所有副本内容相同 → 合并为 1 组；payload 文件为 i=10..2990 共 299 个
    groups = req("GET", f"/scans/{scan_id}/duplicates?status=CONFIRMED")
    expected_members = n_files // 10 - 1
    check("确认重复组数量符合预期", len(groups) == 1 and len(groups[0]["members"]) == expected_members,
          f"{len(groups)} 组，成员 {len(groups[0]['members']) if groups else 0}（预期 1 组 {expected_members} 成员）")

    # 3. 计划：执行 → 安全区登记 → 撤销 → 还原
    g = max(groups, key=lambda x: x["totalSize"])
    members = len(g["members"])
    plan = req("POST", "/plans", {"kind": "DUPLICATE_CLEANUP", "refId": g["id"],
                                  "keepStrategy": "NEWEST"})
    count_before = sum(len(fs) for _, _, fs in os.walk(BENCH))
    t0 = time.time()
    executed = req("POST", f"/plans/{plan['id']}/execute")
    exec_t = time.time() - t0
    check("计划执行成功", executed["status"] == "EXECUTED", f"{exec_t * 1000:.0f}ms")
    q_after = req("GET", "/quarantine")
    check("安全区收到全部副本", len(q_after) == members - 1, f"{len(q_after)}/{members - 1}")

    t0 = time.time()
    rolled = req("POST", f"/plans/{plan['id']}/rollback")
    rb_t = time.time() - t0
    check("撤销成功", rolled["status"] == "ROLLED_BACK", f"{rb_t * 1000:.0f}ms")
    count_after = sum(len(fs) for _, _, fs in os.walk(BENCH))
    check("撤销后文件全部还原", count_before == count_after,
          f"{count_before} → {count_after}")
    q_rolled = req("GET", "/quarantine")
    check("撤销后安全区清空", len(q_rolled) == 0, f"{len(q_rolled)}")

    # 4. 增量扫描（内容未变 → 复用哈希）
    scan2_id, row2, scan2_t = run_scan(BENCH)
    stats2 = json.loads(row2["statsJson"] or "{}")
    check("增量扫描哈希复用", stats2.get("reusedHashes", 0) > 0,
          f"二次扫描 {scan2_t:.2f}s，复用 {stats2.get('reusedHashes')} 个哈希")

    # 5. 目录监控自动整理
    rule = req("POST", "/rules", {
        "id": 0, "name": "watch-image", "priority": 1, "enabled": True,
        "conditions": [{"field": "TYPE", "op": "EQ", "value": "IMAGE"}],
        "action": {"type": "MOVE", "targetTemplate": "{type}", "tag": None}})
    shutil.rmtree(WATCH, ignore_errors=True)
    os.makedirs(WATCH)
    req("POST", "/watch", {"dir": WATCH})
    time.sleep(0.8)
    png = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
        "AAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
    with open(os.path.join(WATCH, "pic.png"), "wb") as f:
        f.write(png)
    deadline = time.time() + 10
    moved = False
    while time.time() < deadline and not moved:
        moved = os.path.exists(os.path.join(WATCH, "image", "pic.png"))
        time.sleep(0.3)
    plans = req("GET", "/plans")
    auto = [p for p in plans if p["description"] and "自动整理" in (p["description"] or "")]
    check("监控自动整理落盘", moved, f"自动计划 {len(auto)} 个")
    check("自动计划可撤销", auto and auto[0]["status"] in ("EXECUTED", "PLANNED"),
          auto[0]["status"] if auto else "无")
    req("DELETE", "/watch?dir=" + WATCH)

    # 6. 仪表盘
    dash = req("GET", "/dashboard")
    check("仪表盘数据就绪", dash["indexedFiles"] == n_files and dash["totalScans"] == 2,
          f"files={dash['indexedFiles']} scans={dash['totalScans']}")

    print(f"\n耗时汇总：首次扫描 {scan_t:.2f}s | 撤销扫描 {scan2_t:.2f}s | "
          f"执行 {exec_t * 1000:.0f}ms | 撤销 {rb_t * 1000:.0f}ms")
    if failures:
        print("FAILED: " + ", ".join(failures))
        sys.exit(1)
    print("E2E 全部通过 ✓")


if __name__ == "__main__":
    main()
