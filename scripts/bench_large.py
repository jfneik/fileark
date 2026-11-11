#!/usr/bin/env python3
"""FileArk 大规模基准：衡量文件数量维度的扫描吞吐、增量复用与内存占用。

与 e2e_bench.py 的区别：本脚本只测性能不做功能断言，规模更大（默认 2 万文件），
文件以小文件为主（元数据与逐文件开销主导），并混入 20 组共享载荷制造重复。

用法：先启动后端，然后 python3 scripts/bench_large.py [N_FILES]
输出：各阶段耗时、吞吐（文件/秒、MB/秒）、增量复用数、服务进程 RSS。
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api"
BENCH = "/tmp/fileark-large"
N_FILES = int(sys.argv[1]) if len(sys.argv) > 1 else 20000
DUP_EVERY = 4          # 每 4 个文件 1 个重复副本
N_PAYLOADS = 20        # 20 组共享载荷 → 上百个重复组
PID_FILE = os.path.expanduser("~/.fileark/server.pid")


def req(method, path, body=None, timeout=300):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def gen_tree():
    shutil.rmtree(BENCH, ignore_errors=True)
    payloads = [os.urandom(16 * 1024) for _ in range(N_PAYLOADS)]
    n_dirs = 50
    total = 0
    t0 = time.time()
    for i in range(N_FILES):
        d = os.path.join(BENCH, f"d{i % n_dirs}")
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, f"f{i}.bin")
        if i % DUP_EVERY == 0:
            data = payloads[i % N_PAYLOADS]
        else:
            data = os.urandom(1024 * (1 + (i * 7) % 20))  # 1–20KB 小文件
        with open(p, "wb") as f:
            f.write(data)
        total += len(data)
    return total, time.time() - t0


def run_scan(label):
    t0 = time.time()
    sid = req("POST", "/scans", {"rootPath": BENCH})["scanId"]
    while True:
        row = req("GET", f"/scans/{sid}")
        if row["status"] != "RUNNING":
            break
        time.sleep(0.5)
    return sid, row, time.time() - t0


def server_rss_mb():
    try:
        pid = open(PID_FILE).read().strip()
        out = subprocess.run(["ps", "-o", "rss=", "-p", pid],
                             capture_output=True, text=True).stdout.strip()
        return int(out) / 1024 if out else -1
    except Exception:
        return -1


def fmt(n):
    return f"{n:,}"


def main():
    total_bytes, gen_t = gen_tree()
    print(f"合成目录：{fmt(N_FILES)} 文件 / {total_bytes / 1e6:.0f} MB（生成 {gen_t:.1f}s）")

    _, row1, t1 = run_scan("首次全量")
    s1 = json.loads(row1["statsJson"] or "{}")
    print(f"首次扫描  ：{t1:.1f}s | {fmt(s1.get('files', 0))} 文件 | "
          f"{s1.get('confirmedGroups', 0)} 重复组 | 可释放 {s1.get('reclaimableBytes', 0) / 1e6:.0f} MB")

    _, row2, t2 = run_scan("增量")
    s2 = json.loads(row2["statsJson"] or "{}")
    reused = s2.get("reusedHashes", 0)
    dup_files = N_FILES // DUP_EVERY
    print(f"二次扫描  ：{t2:.1f}s | 复用 {fmt(reused)} 个全量哈希"
          f"（重复文件 {fmt(dup_files)}，复用率 {reused / max(1, dup_files) * 100:.0f}%）")

    rss = server_rss_mb()
    print(f"服务内存  ：RSS ≈ {rss:.0f} MB")
    print(f"吞吐      ：{N_FILES / t1:,.0f} 文件/秒 | {total_bytes / 1e6 / t1:.1f} MB/秒（首次扫描）")
    shutil.rmtree(BENCH, ignore_errors=True)
    print("基准目录已清理")


if __name__ == "__main__":
    main()
