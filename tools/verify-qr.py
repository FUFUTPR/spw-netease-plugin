#!/usr/bin/env python3
"""二维码真解码校验：用 OpenCV 的 QRCodeDetector 解码 tools/smoke/out-qr 下探针产出的 PNG，
与 expect-*.txt 逐字比对。

为什么需要它：0.2.0 的 QrCode 产出纯白图（黑像素 0），UI/日志都看不出问题，只有真解码能发现。

用法：
    python tools/verify-qr.py [outDir]        # 默认 tools/smoke/out-qr

退出码：0 = 全部通过；1 = 有 FAIL；2 = 环境缺 cv2（优雅跳过，不算失败）
输出：每例一行 `[PY] case=<名> ... state=PASS|FAIL`，末尾 `[PY] SUMMARY pass= fail= skip=`
"""
import os
import sys
import unicodedata

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join("tools", "smoke", "out-qr")


def load_cv2():
    try:
        import cv2  # noqa
        return cv2
    except Exception as exc:  # pragma: no cover
        print("[PY] cv2 不可用（%s）——跳过真解码，退出码 2" % exc)
        return None


def decode(cv2, path):
    """返回解码字符串（失败为 None）。

    注意：OpenCV 在 Windows 上打不开含非 ASCII 字符的路径（本仓库路径里有中文），
    所以先用 Python 读字节，再交给 cv2.imdecode。
    """
    try:
        import numpy as np
    except Exception:
        np = None
    if np is not None:
        try:
            buf = np.frombuffer(open(path, "rb").read(), dtype=np.uint8)
            img = cv2.imdecode(buf, cv2.IMREAD_GRAYSCALE)
        except Exception as exc:
            print("[PY] case=? state=WARN reason=imdecode 异常 %s" % exc)
            img = None
    else:
        img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    if img is None:
        return None
    det = cv2.QRCodeDetector()
    data, _pts, _straight = det.detectAndDecode(img)
    return data or None


def as_text(raw):
    """按 UTF-8 重新解释（部分 OpenCV 版本把 byte 段解成 Latin-1）。"""
    try:
        return raw.encode("latin-1").decode("utf-8")
    except Exception:
        return raw


def main():
    cv2 = load_cv2()
    if cv2 is None:
        return 2
    if not os.path.isdir(DIR):
        print("[PY] 目录不存在：%s" % DIR)
        return 1

    names = sorted(
        f[3:-4] for f in os.listdir(DIR)
        if f.startswith("qr-") and f.endswith(".png")
    )
    if not names:
        print("[PY] 没找到 qr-*.png（探针没跑？）")
        return 1

    passed = failed = skipped = 0
    for name in names:
        png = os.path.join(DIR, "qr-%s.png" % name)
        exp_path = os.path.join(DIR, "expect-%s.txt" % name)
        if not os.path.isfile(exp_path):
            skipped += 1
            print("[PY] case=%s state=SKIP reason=缺 expect-%s.txt" % (name, name))
            continue
        with open(exp_path, "rb") as fh:
            expect = fh.read().decode("utf-8").rstrip("\r\n")

        raw = decode(cv2, png)
        if raw is None:
            failed += 1
            print("[PY] case=%s state=FAIL reason=解码器读不出内容（纯白/结构损坏）" % name)
            continue
        got = raw if raw == expect else as_text(raw)
        ok = unicodedata.normalize("NFC", got) == unicodedata.normalize("NFC", expect)
        print("[PY] case=%s state=%s decoded=%s expect=%s"
              % (name, "PASS" if ok else "FAIL", ascii(got), ascii(expect)))
        if ok:
            passed += 1
        else:
            failed += 1

    print("[PY] SUMMARY pass=%d fail=%d skip=%d" % (passed, failed, skipped))
    return 0 if failed == 0 and passed > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
