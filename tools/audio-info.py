#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只读：判一份音频文件「到底什么音质」—— FLAC 解 STREAMINFO、MP3 解 MPEG 帧头。

用法：
    python tools/audio-info.py <文件> [<文件> …]

为什么需要它：网易云把「无损」也按母带的采样率交付，同一账号下
`lossless` 可能是 44.1 kHz/16 bit，`hires` 才是 48/96 kHz/24 bit；
「音频流 44.1 kHz」只能说明采样率，说明不了档位。判音质的唯一硬判据是
**文件自己的头**，并且要和接口自报的档位字节数对齐（见 docs/08 §36）。
"""
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def flac_info(path):
    with open(path, "rb") as f:
        head = f.read(64)
    if len(head) < 42 or head[:4] != b"fLaC":
        return None
    btype = head[4] & 0x7F
    blen = int.from_bytes(head[5:8], "big")
    if btype != 0 or blen != 34:
        return f"非 STREAMINFO 开头（type={btype} len={blen}）"
    p = head[8:8 + 34]
    rate = (p[10] << 12) | (p[11] << 4) | (p[12] >> 4)
    ch = ((p[12] >> 1) & 0x07) + 1
    bps = (((p[12] & 0x01) << 4) | (p[13] >> 4)) + 1
    total = ((p[13] & 0x0F) << 32) | int.from_bytes(p[14:18], "big")
    dur = total / rate if rate else 0.0
    return (f"FLAC {rate} Hz / {bps} bit / {ch} ch / 总样本 {total} / 时长 {dur:.1f}s"
            f"（min/max block={int.from_bytes(p[0:2], 'big')}/{int.from_bytes(p[2:4], 'big')}）")


def mp3_info(path):
    """解析 ID3v2 之后的第一个 MPEG 帧头（客户端「下载」出来的多是这一档）。"""
    with open(path, "rb") as f:
        first = f.read(10)
        if first[:3] == b"ID3" and len(first) == 10:
            size = ((first[6] & 0x7F) << 21) | ((first[7] & 0x7F) << 14) \
                   | ((first[8] & 0x7F) << 7) | (first[9] & 0x7F)
            f.seek(10 + size + (10 if (first[5] & 0x10) else 0))
        head = f.read(64 * 1024)
        base = f.tell() - len(head)
    i = 0
    while i + 4 <= len(head):
        if head[i] == 0xFF and (head[i + 1] & 0xE0) == 0xE0:
            b1, b2 = head[i + 1], head[i + 2]
            version = (b1 >> 3) & 0x03
            layer = (b1 >> 1) & 0x03
            bitrate_idx = (b2 >> 4) & 0x0F
            rate_idx = (b2 >> 2) & 0x03
            mode = (head[i + 3] >> 6) & 0x03
            rates = {3: [44100, 48000, 32000], 2: [22050, 24000, 16000], 0: [11025, 12000, 8000]}
            v1_l3 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0]
            v2_l3 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0]
            if version in rates and rate_idx != 3 and bitrate_idx not in (0, 15) and layer == 1:
                rate = rates[version][rate_idx]
                br = (v1_l3 if version == 3 else v2_l3)[bitrate_idx]
                ch = 1 if mode == 3 else 2
                ver = {3: "MPEG1", 2: "MPEG2", 0: "MPEG2.5"}[version]
                return f"MP3 {ver} Layer3 {rate} Hz / {br} kbps / {ch} ch（首个帧头在该文件偏移 {base + i}）"
        i += 1
    if first[:3] == b"ID3":
        return "MP3（ID3v2 后 64KB 内未见帧头）"
    return None


def mp4_info(path):
    """MP4/ISO-BMFF 家族（网易云「杜比全景声」这一档就是 av3a/mp4）。"""
    with open(path, "rb") as f:
        head = f.read(64)
    if len(head) >= 12 and head[4:8] == b"ftyp":
        brand = head[8:12].decode("ascii", "replace")
        return f"MP4/ISO-BMFF brand={brand}（杜比全景声/av3a 这类容器，能否播放取决于播放器的编解码支持）"
    return None


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    for path in sys.argv[1:]:
        if not os.path.isfile(path):
            print(f"[missing] {path}")
            continue
        size = os.path.getsize(path)
        info = flac_info(path) or mp3_info(path) or mp4_info(path) \
            or "未知容器（既不是 FLAC / MP3，也不是 MP4）"
        print(f"{os.path.basename(path)[:70]:<72} {size:>12,} B  {info}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
