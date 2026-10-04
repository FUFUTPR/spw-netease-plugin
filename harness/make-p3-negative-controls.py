#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P3 阴性对照构造器 —— 生成「故意破坏 P3 新增功能面」的 .spmod 副本，
用来证明 tools/verify-spmod.ps1 的 [P10.x] 与 harness 的 [H9.x]/[H10.x] 真的会报 FAIL。

为什么需要：只会说 PASS 的校验器没有价值。P3 新增的每一项都必须被证明「错了会被抓」。

设计原则：
  · 每一份对照**只破坏一处**，破坏得尽量「干净」，让失败能精确定位到某一项检查；
  · 本脚本的阴性对照全部落在**现存**功能面（0.5.0 起歌词 / 匹配 / 下载 / 打标链路已整体删除，
    原对照会因目标组/控件不存在而静默跳过 —— 跳过的对照等于没有对照）；
  · 对照 a 故意破坏**非 P3 控件**的 on_click（维护组「测试网络连通性」）—— 用来证明 harness 的 H9.4
    覆盖面比 verify-spmod 的 P10.x 更宽：H9.4 把 preference_config.json 里**每个** on_click 都真反射一遍，
    而 verify-spmod 只做指定入口的静态字符串比对，所以这一份对照在 verify-spmod 侧应当 PASS；
  · 对照 b 删掉在线播放控件的档位清单 —— 证明 P10.8 真的会抓；
  · 对照 e 破坏 P3 控件本身（0.11.35 起 = 账号组「当前账号」按钮的接线）—— 证明两层都抓得到，
    且失败信息里带控件标题；
  · 本脚本【只读】build/dist 下的真实产物（以及 -Spmod 指定的副本），输出一律写 harness/tmp/。

用法：
    python harness/make-p3-negative-controls.py [源 spmod 路径]
    # 缺省源 = build/dist 下最新的 *.spmod
"""
import glob
import json
import os
import shutil
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TMP = os.path.join(HERE, "tmp")

PREFS = "classes/preference_config.json"
ACCOUNT_SVC = "classes/com/example/netease/svc/AccountService.class"


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


def group_of(doc, config_name):
    for g in doc.get("configs", []):
        if g.get("config") == config_name:
            return g
    return None


def find_pref(group, key=None, title=None):
    for p in (group or {}).get("preferences", []):
        if key is not None and p.get("key") == key:
            return p
        if title is not None and p.get("title") == title:
            return p
    return None


def dump(doc):
    return json.dumps(doc, ensure_ascii=False, indent=2).encode("utf-8")


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else newest_spmod()
    src = os.path.abspath(src)
    base = read_zip(src)
    if PREFS not in base:
        sys.exit(f"源产物里没有 {PREFS}")
    doc0 = json.loads(base[PREFS].decode("utf-8"))
    print(f"源产物: {src}  ({os.path.getsize(src)} bytes)")
    os.makedirs(TMP, exist_ok=True)
    made = []

    # --- 对照 a：破坏【非 P3】控件的 on_click → 期望只有 harness H9.4 FAIL ---
    # 选「维护」组的「测试网络连通性」：它是现存控件、不在 P3 入口清单里，
    # 且改的只是方法名（类名不动）→ P10.11/P10.12 仍应 PASS，只有 H9.4 反射时暴露。
    e = dict(base)
    doc = json.loads(base[PREFS].decode("utf-8"))
    g = group_of(doc, "config.json")
    p = find_pref(g, title="测试网络连通性")
    if p is None or "on_click" not in p:
        print("  [a] 跳过：没找到「测试网络连通性」控件")
    else:
        old = p["on_click"]
        p["on_click"] = old + "XX"          # 方法名不存在
        e[PREFS] = dump(doc)
        pa = os.path.join(TMP, "neg-p3-a-bad-onclick-nonp3.spmod")
        write_zip(pa, e)
        print(f"  [a] {os.path.basename(pa)}  {old} → {p['on_click']}（现存非 P3 控件，verify-spmod 不该 FAIL）")
        made.append((pa, "非 P3 控件 on_click 方法名不存在", "harness H9.4（verify-spmod 应 PASS）", "-SkipVerify"))

    # --- 对照 b：删掉 audio_level 的 entry_values 档位清单 → 期望 verify-spmod [P10.8] FAIL ---
    # 0.11.7：audio_level 已从 client_cfg.json（客户端组，整组删除）迁到 config.json（维护组）。
    e = dict(base)
    doc = json.loads(base[PREFS].decode("utf-8"))
    g = group_of(doc, "config.json")
    p = find_pref(g, key="audio_level")
    if g is None or p is None:
        print("  [b] 跳过：没有 config.json 组或组里没有 audio_level")
    elif "entry_values" not in p:
        print("  [b] 跳过：audio_level 控件本来就没有 entry_values")
    else:
        del p["entry_values"]
        e[PREFS] = dump(doc)
        pb = os.path.join(TMP, "neg-p3-b-no-audio-level-entries.spmod")
        write_zip(pb, e)
        print(f"  [b] {os.path.basename(pb)}  删除「在线播放音质」的 entry_values 档位清单")
        made.append((pb, "audio_level 控件缺 entry_values 档位", "verify-spmod P10.8", ""))

    # --- 对照 c：auto_login 的 type 改成非法值 → 期望 verify-spmod [P10.5] FAIL ---
    # 0.11.7：账号组控件由 auto_client（已删）换成 auto_login（启动时自动登录）；
    # 0.11.46：auto_login 从 account_cfg.json 组搬进 config.json 组（只随开发者档显示）。
    e = dict(base)
    doc = json.loads(base[PREFS].decode("utf-8"))
    p = find_pref(group_of(doc, "config.json"), key="auto_login")
    if p is None:
        print("  [c] 跳过：config.json 组里没有 auto_login")
    else:
        old = p.get("type")
        p["type"] = "switch_x"
        e[PREFS] = dump(doc)
        pc = os.path.join(TMP, "neg-p3-c-bad-switch-type.spmod")
        write_zip(pc, e)
        print(f"  [c] {os.path.basename(pc)}  auto_login 的 type: {old!r} → 'switch_x'")
        made.append((pc, "auto_login 控件类型非法", "verify-spmod P10.5", ""))

    # --- 对照 d：删掉 svc/AccountService.class → 期望 verify-spmod [P10.10] FAIL ---
    e = dict(base)
    if ACCOUNT_SVC not in e:
        print(f"  [d] 跳过：产物里没有 {ACCOUNT_SVC}")
    else:
        del e[ACCOUNT_SVC]
        pd = os.path.join(TMP, "neg-p3-d-no-accountservice.spmod")
        write_zip(pd, e)
        print(f"  [d] {os.path.basename(pd)}  删除 {ACCOUNT_SVC}")
        made.append((pd, "P3 类 AccountService.class 缺失", "verify-spmod P10.10", ""))

    # --- 对照 e：破坏 P3 控件「当前账号」的 on_click → 期望两层都 FAIL，且失败行带控件标题 ---
    # 0.11.7：客户端接入控件已删，改用账号组原生登录入口。
    # 0.11.9：手机号 + 密码登录已退役，账号组里剩下的 P3 控件是短信验证码入口，对照改指向它。
    # 0.11.22：登录拆成「获取验证码」+「确定登录」两行，对照取前者。
    # 0.11.23：登录块收成一行（两个按钮退役，登录侧零入口）⇒ 对照改取账号组剩下的按钮「账号状态」。
    # 0.11.35：账号行改成 button「当前账号」→ NeteasePlugin.currentAccount（点开 ui/AccountStatusWindow
    #          展示账号名字/ID/会员信息，页面上不再常驻登录态），「账号状态」按钮与 accountStatus 一起删除
    #          ⇒ 对照改破坏「当前账号」行的 on_click（currentAccountX：方法名不存在，依旧是「接线漂移」
    #          这一类故障的等价样本）。行定位也从 title 找 —— 0.11.35 起账号行没有 key 字段。
    # 期望红（0.11.35 现状，2026-10-02 核对）：
    #   verify-spmod [P10.5c]（$curOk 要求 on_click **精确等于** …currentAccount）+ [P10.7]（本项就是查这
    #   一行的接线字符串），失败行文案带控件标题「当前账号」；
    #   harness [H9.3]（USER_TARGETS 里的 …currentAccount 在用户档里消失 ⇒「缺失：…currentAccount」）
    #   + [H9.4]（每个 on_click 真反射一遍 ⇒ 用户档那一条 currentAccountX 无方法）
    #   + [H9.13]（账号行形态断言：on_click 不是 …currentAccount ⇒ devBad 非空）。
    e = dict(base)
    doc = json.loads(base[PREFS].decode("utf-8"))
    p = find_pref(group_of(doc, "account_cfg.json"), title="当前账号")
    if p is None or "on_click" not in p:
        print("  [e] 跳过：account_cfg.json 组里没有「当前账号」按钮（0.11.35 起应有；先查构建）")
    else:
        old = p["on_click"]
        p["on_click"] = "com.example.netease.NeteasePlugin.currentAccountX"
        e[PREFS] = dump(doc)
        pe = os.path.join(TMP, "neg-p3-e-bad-p3-onclick.spmod")
        write_zip(pe, e)
        print(f"  [e] {os.path.basename(pe)}  {old} → {p['on_click']}（P3 控件「当前账号」）")
        made.append((pe, "P3 控件 on_click 方法名不存在",
                     "verify-spmod P10.5c+P10.7 / harness H9.3+H9.4+H9.13", ""))

    # --- 对照 f：把 0.11.10 已退役的临时探针组塞回配置页 → 期望 verify-spmod [P10.1]+[P10.19] 双红、harness [H9.12]+[H9.4] 双红 ---
    # 0.11.10：探针组整组退役（配置页第 5 组 + probeP2..P5 四个静态入口 + svc.ProbeWindow/ProbeCover）。
    # 本对照复刻「回潮」样本：组 + 控件 + 接线都在，但被调的方法已随退役删除 ⇒
    # 静态字符串层（P10.19）与真反射层（H9.12 的反射查方法、H9.4 的接线可调用）各抓一次。
    e = dict(base)
    doc = json.loads(base[PREFS].decode("utf-8"))
    probe_group = {
        "title": "探针（临时，验收后删除）",
        "config": "probe.json",
        "preferences": [
            {"type": "button", "title": t, "summary": "阴性对照：探针回潮",
             "arrow_type": "none", "on_click": "com.example.netease.NeteasePlugin." + m}
            for t, m in (("P-2 宿主窗口/模态框", "probeP2"), ("P-3 宿主播放队列", "probeP3"),
                         ("P-4 播放条取图链路", "probeP4"), ("P-5 预热解耦验证", "probeP5"))
        ],
    }
    if group_of(doc, "probe.json") is not None:
        print("  [f] 跳过：源产物里本来就有 probe.json 组（不该发生，先查构建）")
    else:
        doc["configs"].append(probe_group)
        e[PREFS] = dump(doc)
        pf = os.path.join(TMP, "neg-p3-f-probe-group-revives.spmod")
        write_zip(pf, e)
        print(f"  [f] {os.path.basename(pf)}  塞回 probe.json 组（4 个 button → probeP2..P5）")
        made.append((pf, "临时探针组回潮", "verify-spmod P10.1 + P10.19 / harness H9.12 + H9.4", "-SkipVerify"))

    print(f"\n共生成 {len(made)} 份 P3 阴性对照 → {TMP}")
    if len(made) < 3:
        print("  提示：对照生成数偏少，先确认 build/dist 产物与 src/main/resources/preference_config.json 同代")
    for path, what, expect, harness_arg in made:
        print(f"  {os.path.basename(path):38s} {what:26s} 期望被 {expect} 判 FAIL")
    print("\n提示：harness 需要 -SkipVerify 才能用被 verify-spmod 判 FAIL 的副本单独取证。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
