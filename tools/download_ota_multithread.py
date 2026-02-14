#!/usr/bin/env python3
import argparse
import concurrent.futures
import hashlib
import os
import re
import sys
import time
import urllib.request


def parse_args():
    p = argparse.ArgumentParser(description="Multi-thread range downloader for OTA zip.")
    p.add_argument("--url", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--threads", type=int, default=8)
    p.add_argument("--retries", type=int, default=8)
    p.add_argument("--timeout", type=int, default=45)
    p.add_argument("--md5", default="")
    return p.parse_args()


def get_total_size(url, timeout):
    req = urllib.request.Request(
        url,
        headers={
            "Range": "bytes=0-0",
            "Accept-Encoding": "identity",
            "Connection": "keep-alive",
            "User-Agent": "curl/8.0",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        cr = resp.headers.get("Content-Range", "")
        if cr:
            m = re.match(r"bytes\s+\d+-\d+/(\d+)", cr)
            if m:
                return int(m.group(1))
        cl = resp.headers.get("Content-Length")
        if cl:
            return int(cl)
    raise RuntimeError("Cannot determine total size from response headers.")


def chunk_plan(total, n):
    part = total // n
    ranges = []
    start = 0
    for i in range(n):
        end = start + part - 1
        if i == n - 1:
            end = total - 1
        ranges.append((start, end))
        start = end + 1
    return ranges


def part_path(out_file, idx):
    return f"{out_file}.part{idx:03d}"


def part_size(path):
    if not os.path.exists(path):
        return 0
    return os.path.getsize(path)


def fetch_range(url, start, end, out_file, timeout, retries):
    expected = end - start + 1
    existing = part_size(out_file)
    if existing == expected:
        return
    if existing > expected:
        os.remove(out_file)
        existing = 0

    for attempt in range(1, retries + 1):
        try:
            resume_start = start + existing
            if resume_start > end:
                return
            req = urllib.request.Request(
                url,
                headers={
                    "Range": f"bytes={resume_start}-{end}",
                    "Accept-Encoding": "identity",
                    "Connection": "keep-alive",
                    "User-Agent": "curl/8.0",
                },
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                code = getattr(resp, "status", 200)
                if code != 206:
                    raise RuntimeError(f"HTTP {code}")
                cr = resp.headers.get("Content-Range", "")
                expected_prefix = f"bytes {resume_start}-"
                if not cr.startswith(expected_prefix):
                    raise RuntimeError(f"Bad Content-Range: {cr}")
                mode = "ab" if existing > 0 else "wb"
                with open(out_file, mode) as f:
                    while True:
                        block = resp.read(1024 * 1024)
                        if not block:
                            break
                        f.write(block)
            got = part_size(out_file)
            if got == expected:
                return
            if got > expected:
                raise RuntimeError(f"Chunk oversize {got}>{expected}")
            existing = got
            raise RuntimeError(f"Chunk short read {got}/{expected}")
        except Exception:
            if attempt == retries:
                raise
            time.sleep(min(2 * attempt, 10))


def merge_parts(out_file, ranges):
    with open(out_file, "wb") as out:
        for i, (start, end) in enumerate(ranges):
            p = part_path(out_file, i)
            expected = end - start + 1
            got = part_size(p)
            if got != expected:
                raise RuntimeError(f"Part {i} size mismatch: {got}/{expected}")
            with open(p, "rb") as f:
                while True:
                    b = f.read(1024 * 1024)
                    if not b:
                        break
                    out.write(b)


def md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        while True:
            b = f.read(1024 * 1024)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def progress_loop(out_file, total, stop, part_count):
    while not stop["done"]:
        got = 0
        for i in range(part_count):
            got += part_size(part_path(out_file, i))
        if got > total:
            got = total
        pct = (got * 100.0 / total) if total else 0.0
        sys.stdout.write(f"\rprogress: {got}/{total} ({pct:.2f}%)")
        sys.stdout.flush()
        time.sleep(2)
    sys.stdout.write("\n")


def main():
    args = parse_args()
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    total = get_total_size(args.url, args.timeout)
    ranges = chunk_plan(total, max(1, args.threads))
    print(f"total={total} bytes, threads={len(ranges)}")

    # Cleanup stale part files from previous runs with different thread counts.
    out_dir = os.path.dirname(args.out) or "."
    base = os.path.basename(args.out)
    stale_prefix = base + ".part"
    for fn in os.listdir(out_dir):
        if not fn.startswith(stale_prefix):
            continue
        idx_text = fn[len(stale_prefix) :]
        if not idx_text.isdigit():
            continue
        idx = int(idx_text)
        if idx >= len(ranges):
            os.remove(os.path.join(out_dir, fn))

    stop = {"done": False}
    t = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    prog = t.submit(progress_loop, args.out, total, stop, len(ranges))

    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(ranges)) as pool:
            futures = []
            for i, (start, end) in enumerate(ranges):
                p = part_path(args.out, i)
                expected = end - start + 1
                if part_size(p) == expected:
                    continue
                futures.append(
                    pool.submit(fetch_range, args.url, start, end, p, args.timeout, args.retries)
                )
            for f in concurrent.futures.as_completed(futures):
                f.result()

        merge_parts(args.out, ranges)
        final_size = part_size(args.out)
        if final_size != total:
            raise RuntimeError(f"Final size mismatch: {final_size}/{total}")
        print(f"file_ok size={final_size}")

        if args.md5:
            digest = md5_file(args.out)
            print(f"md5={digest}")
            if digest.lower() != args.md5.lower():
                raise RuntimeError(f"MD5 mismatch: expected {args.md5}, got {digest}")
            print("md5_ok")
    finally:
        stop["done"] = True
        prog.result(timeout=10)
        t.shutdown(wait=False)


if __name__ == "__main__":
    main()

