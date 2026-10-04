#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""品红标记像素扫描（B3 轮 E13/A17 判读用，只读，不写任何宿主状态）。

用途
====
探针 P-4 的标记图实验（`probe-p4-marker.flag` = 磁盘键替换 / `probe-p4-push-marker.flag`
= 路线② 直投播放条）会把一张「256×256 品红底 + 12px 白边」的标记图临时放进宿主缓存或
图片流。宿主窗口截图后，用本脚本数**品红像素**，即可机器判定「UI 有没有真的消费我们的键/投递」：

  · 播放条带（窗口底部 band 比例区）出现上千品红像素 ⇒ 路线② 在 UI 上可见（A17 成立）
  · 列表区出现成片品红 ⇒ 路线①（写 shared_cover 键 ⇒ UI 出图）成立（E13）
  · 参考基线：B2 轮播放条带品红 = 8 px（噪声级）

判据色：品红 = R≥200 且 B≥200 且 G≤70（脚本不判断、只出数）。

用法
====
  python tools/smoke/magenta-scan.py <png> [<png> ...] [--band-from 0.88] [--top 10] [--json out.json]

输出：每图一行摘要 + 最密行/列（用于定位品红出现在截图的哪个区域）+ 可选 JSON。
"""

import argparse
import json
import sys

import numpy as np
from PIL import Image


def scan(path, band_from_frac, top):
    im = Image.open(path).convert("RGB")
    arr = np.asarray(im, dtype=np.int16)
    h, w = arr.shape[0], arr.shape[1]
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    mask = (r >= 200) & (b >= 200) & (g <= 70)
    total = int(mask.sum())
    band_y = int(h * band_from_frac)
    in_band = int(mask[band_y:, :].sum())
    out = {
        "png": path,
        "w": w,
        "h": h,
        "magenta_total": total,
        "band_from_y": band_y,
        "magenta_in_band": in_band,
    }
    if total > 0:
        ys, xs = np.nonzero(mask)
        out["bbox"] = {
            "x": [int(xs.min()), int(xs.max())],
            "y": [int(ys.min()), int(ys.max())],
        }
        rows = mask.sum(axis=1)
        cols = mask.sum(axis=0)
        top_rows = np.argsort(rows)[::-1][:top]
        top_cols = np.argsort(cols)[::-1][:top]
        out["top_rows"] = [
            {"y": int(y), "n": int(rows[y])} for y in sorted(top_rows) if rows[y] > 0
        ]
        out["top_cols"] = [
            {"x": int(x), "n": int(cols[x])} for x in sorted(top_cols) if cols[x] > 0
        ]
    return out


def render(o):
    lines = [
        "  {png}: 尺寸 {w}x{h} | 全图品红 {total} px | 播放条带(y>={by}) {band} px".format(
            png=o["png"],
            w=o["w"],
            h=o["h"],
            total=o["magenta_total"],
            by=o["band_from_y"],
            band=o["magenta_in_band"],
        )
    ]
    if o["magenta_total"] > 0:
        bb = o["bbox"]
        lines.append("      品红包围盒 x=[{x0},{x1}] y=[{y0},{y1}]".format(
            x0=bb["x"][0], x1=bb["x"][1], y0=bb["y"][0], y1=bb["y"][1]))
        lines.append("      最密行：" + " / ".join(
            "y={y} n={n}".format(**t) for t in o.get("top_rows", [])))
        lines.append("      最密列：" + " / ".join(
            "x={x} n={n}".format(**t) for t in o.get("top_cols", [])))
    else:
        lines.append("      （零品红像素）")
    return lines


def main(argv=None):
    ap = argparse.ArgumentParser(description="数截图里的品红标记像素（只读）")
    ap.add_argument("png", nargs="+", help="PNG 路径（可多个）")
    ap.add_argument("--band-from", type=float, default=0.88,
                    help="播放条带起点（窗口高度比例，缺省 0.88）")
    ap.add_argument("--top", type=int, default=8, help="输出最密行/列条数")
    ap.add_argument("--json", default=None, help="把结果写成 JSON")
    a = ap.parse_args(argv)

    results = []
    for p in a.png:
        try:
            o = scan(p, a.band_from, a.top)
        except Exception as e:                      # 截图坏/无文件都不该让整轮取证失败
            print("  {0}: 读图失败：{1}".format(p, e))
            results.append({"png": p, "error": str(e)})
            continue
        results.append(o)
        for line in render(o):
            print(line)
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
        print("  JSON: {0}".format(a.json))
    return 0


if __name__ == "__main__":
    sys.exit(main())
