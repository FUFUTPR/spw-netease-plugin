#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阴性对照构造器 —— 生成「故意破坏」的 .spmod 副本，用来证明 tools/verify-spmod.ps1 真的会报 FAIL。

为什么需要：一个只会说 PASS 的校验器没有任何价值。必须证明它对每一类缺陷都会拒绝。
本脚本【只读】build/dist 下的真实产物，产物副本写在 harness/tmp/ 下（绝不污染 build/dist）。

用法：
    python harness/make-negative-controls.py [源 spmod 路径]
    # 缺省源 = build/dist 下最新的 *.spmod
"""
import glob
import io
import json
import os
import shutil
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TMP = os.path.join(HERE, "tmp")

MANIFEST = "META-INF/MANIFEST.MF"
CLASSES_MANIFEST = "classes/META-INF/MANIFEST.MF"
EXT_IDX = "classes/META-INF/extensions.idx"
PREFS = "classes/preference_config.json"


def newest_spmod():
    cands = glob.glob(os.path.join(ROOT, "build", "dist", "*.spmod"))
    if not cands:
        sys.exit("找不到 build/dist/*.spmod，请先构建")
    return max(cands, key=os.path.getmtime)


def read_zip(path):
    with zipfile.ZipFile(path) as z:
        return {i.filename: z.read(i.filename) for i in z.infolist() if not i.is_dir()}


def write_zip(path, entries):
    """原样重写：条目顺序保持，时间戳/压缩方式用固定值以免每次跑出不同字节。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, data)


def unfurl_one_continuation(manifest_bytes):
    """把第一条「续行」的前置空格删掉 —— 模拟折行被破坏。

    续行判据：行首是单个空格，且该行不含 ':'（正常头行形如 'Key: value'）。
    """
    out, hit = [], None
    for raw in manifest_bytes.split(b"\r\n"):
        if hit is None and raw.startswith(b" ") and b":" not in raw:
            out.append(raw[1:])          # 删掉那一个前置空格
            hit = raw
        else:
            out.append(raw)
    return b"\r\n".join(out), hit


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else newest_spmod()
    src = os.path.abspath(src)
    print(f"源产物: {src}  ({os.path.getsize(src)} bytes)")
    base = read_zip(src)
    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(TMP, exist_ok=True)
    made = []

    # --- 对照 1：manifest 折行被破坏（续行前置空格被删）→ 期望 FAIL ---
    # 两份 manifest 必须【同步】改坏：只改根那份的话，P2.2「两份字节一致」会顺带 FAIL，
    # 这个对照就失去精确定位能力了 —— 我们要证明的是「折行破坏能被解折行检查抓到」。
    e = dict(base)
    fixed, hit = unfurl_one_continuation(e[MANIFEST])
    e[MANIFEST] = fixed
    if CLASSES_MANIFEST in e:
        e[CLASSES_MANIFEST], _ = unfurl_one_continuation(e[CLASSES_MANIFEST])
    p1 = os.path.join(TMP, "neg1-manifest-unfold.spmod")
    write_zip(p1, e)
    print(f"  [1] {os.path.basename(p1)}  改动续行: {hit!r} → 去掉前置空格（两份 manifest 同步改）")
    made.append((p1, "manifest 续行前置空格被删", "P1.3（含续行结构检查）"))

    # --- 对照 2：删掉 classes/META-INF/extensions.idx → 期望 FAIL ---
    e = dict(base)
    if EXT_IDX not in e:
        sys.exit(f"源产物里没有 {EXT_IDX}")
    del e[EXT_IDX]
    p2 = os.path.join(TMP, "neg2-no-extensions-idx.spmod")
    write_zip(p2, e)
    print(f"  [2] {os.path.basename(p2)}  删除 {EXT_IDX}")
    made.append((p2, "extensions.idx 缺失", "P3.x"))

    # --- 对照 3：preference_config.json 改成非法 JSON → 期望 FAIL ---
    e = dict(base)
    e[PREFS] = base[PREFS][: len(base[PREFS]) // 2] + b'\n{"configs": [ this is not json '   # 截断 + 语法垃圾
    p3 = os.path.join(TMP, "neg3-bad-json.spmod")
    write_zip(p3, e)
    print(f"  [3] {os.path.basename(p3)}  把 {PREFS} 改成非法 JSON")
    made.append((p3, "preference_config.json 非法 JSON", "P6.x"))

    # --- 对照 4（加强）：删掉 configs[0] 的必填字段 title → 期望 FAIL ---
    e = dict(base)
    doc = json.loads(base[PREFS].decode("utf-8"))
    removed = None
    for g in doc.get("configs", []):
        if "title" in g:
            removed = g.pop("title")
            break
    if removed is None:
        print("  [4] 跳过：configs[] 里没有 title 字段")
    else:
        e[PREFS] = json.dumps(doc, ensure_ascii=False, indent=2).encode("utf-8")
        p4 = os.path.join(TMP, "neg4-missing-field.spmod")
        write_zip(p4, e)
        print(f"  [4] {os.path.basename(p4)}  删掉 configs[0].title（原值 {removed!r}）")
        made.append((p4, "configs[] 缺必填字段 title", "P6.x"))

    print(f"\n共生成 {len(made)} 份阴性对照 → {TMP}")
    for p, what, expect in made:
        print(f"  {os.path.basename(p):34s} {what:28s} 期望被 {expect} 判 FAIL")
    return 0


if __name__ == "__main__":
    sys.exit(main())
