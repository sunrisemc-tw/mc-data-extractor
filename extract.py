#!/usr/bin/env python3
"""mc-data-extractor — 抽取指定 Minecraft 版本的物品清單與原版創造模式分類。

用法:
    python3 extract.py <版本>                # 自動從 Mojang 下載官方 server jar 並提取
    python3 extract.py <版本> --jar 本地.jar # 用本機 jar (vanilla/Paper/Canvas bundler 皆可)
    python3 extract.py --list                # 列出 Mojang manifest 中可用的版本

輸出 (預設 output/<版本>/):
    items.json         全部物品清單 (id, protocol_id, translation_key, is_block,
                       max_stack_size, rarity, max_damage)
    creative-tabs.json 原版創造模式分頁, 每格含精確順序 + 完整 component 資料
                       (NBT 變體如 52 種畫作、旗幟、藥水全部保留)

需求: Python 3.8+, 本機有 JDK 25+ (自動偵測最新 JDK)
原理: 解出 bundler 內的真實 server 核心 jar, 用官方路徑 bootstrap
      (datapack -> tags -> worldgen registries -> DataComponents ->
       CreativeModeTabs.tryRebuildTabContents) 後直接 dump 記憶體中的資料。
"""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.request

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
CACHE_DIR = pathlib.Path.home() / ".cache" / "mc-data-extractor"
UA = {"User-Agent": "mc-data-extractor/1.0"}


def log(msg):
    print(f"[*] {msg}", flush=True)


def http_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return json.load(r)


def download(url, dest, expected_sha1=None):
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".part")
    log(f"下載 {url}")
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    if expected_sha1:
        h = hashlib.sha1(tmp.read_bytes()).hexdigest()
        if h != expected_sha1:
            tmp.unlink()
            raise RuntimeError(f"SHA1 不符: 期望 {expected_sha1}, 實際 {h}")
    tmp.rename(dest)
    log(f"已保存 {dest} ({dest.stat().st_size / 1e6:.1f} MB)")


def resolve_version(version):
    manifest = http_json(MANIFEST_URL)
    ids = {v["id"]: v for v in manifest["versions"]}
    # 相容 "1.26.2" 輸入 → 實際 id "26.2" (新版命名已去掉 1. 前綴)
    candidates = [version]
    if version.startswith("1.") and version[2:] in ids:
        candidates.append(version[2:])
    for cand in candidates:
        if cand in ids:
            return ids[cand]
    latest = manifest.get("latest", {}).get("release", "?")
    raise SystemExit(f"版本 {version} 不在 Mojang manifest 中 (最新 release: {latest}; 可用 --list 查看)")


def newest_jdk():
    """挑選系統上最新版 JDK 的 (java, javac) — 1.26.2 的 runtime jar 需 JDK 25+。"""
    candidates = []
    for base in ("/usr/lib/jvm", "/usr/lib64/jvm"):
        bp = pathlib.Path(base)
        if bp.exists():
            for b in sorted(bp.glob("*")):
                j = b / "bin" / "java"
                if j.exists():
                    candidates.append(j)
    pj = shutil.which("java")
    if pj:
        candidates.append(pathlib.Path(pj).resolve())
    best = None
    for j in candidates:
        try:
            jr = j.resolve()
            out = subprocess.run([str(jr), "-version"], capture_output=True, text=True, timeout=15).stderr
            m = re.search(r'version "(\d+)', out)
            if m and (best is None or int(m.group(1)) > best[0]):
                best = (int(m.group(1)), jr)
        except Exception:
            pass
    if best is None:
        raise SystemExit("找不到 JDK (需要 Java 25+)")
    ver, java = best
    javac = java.with_name("javac")
    if not javac.exists():
        for b in pathlib.Path("/usr/lib/jvm").glob(f"java-{ver}*"):
            if (b / "bin" / "javac").exists():
                javac = b / "bin" / "javac"
    if not javac.exists():
        raise SystemExit("找不到對應的 javac")
    return ver, java, javac


def run(cmd, cwd=None, timeout=600):
    log("執行: " + " ".join(str(c) for c in cmd))
    return subprocess.run([str(c) for c in cmd], cwd=cwd, capture_output=True, text=True, timeout=timeout)


def compile_dump(javac, source, classpath, out_classes):
    out_classes.mkdir(parents=True, exist_ok=True)
    r = run([javac, "-proc:none", "-nowarn", "-Xlint:-options", "-cp", classpath, "-d", out_classes, source])
    if r.returncode != 0:
        sys.stderr.write(r.stdout + r.stderr)
        raise SystemExit("javac 編譯失敗 (見上方輸出)")
    log("DumpCreativeTabs 編譯完成")


def main():
    ap = argparse.ArgumentParser(description="Minecraft 物品/創造分類 JSON 提取器")
    ap.add_argument("version", nargs="?", help="Minecraft 版本, 例如 1.26.2")
    ap.add_argument("--jar", help="使用本機 server jar (vanilla/Paper/Canvas bundler 皆可)")
    ap.add_argument("-o", "--out", help="輸出目錄 (預設 output/<版本>)")
    ap.add_argument("--keep", action="store_true", help="保留 build 暫存檔")
    ap.add_argument("--list", action="store_true", help="列出 Mojang manifest 可用版本")
    args = ap.parse_args()

    if args.list:
        manifest = http_json(MANIFEST_URL)
        print("latest:", manifest["latest"])
        for v in manifest["versions"]:
            if v["type"] in ("release", "snapshot"):
                print(f"  {v['id']:<12} {v['type']}")
        return

    if not args.version:
        ap.error("需要指定版本, 或使用 --list")

    ver, java, javac = newest_jdk()
    log(f"使用 JDK {ver} ({java})")

    version = args.version
    out_dir = pathlib.Path(args.out) if args.out else pathlib.Path("output") / version
    out_dir.mkdir(parents=True, exist_ok=True)
    build_dir = pathlib.Path("build") / version
    if build_dir.exists():
        shutil.rmtree(build_dir)
    build_dir.mkdir(parents=True)

    # ---- 取得 server jar ----
    if args.jar:
        jar = pathlib.Path(args.jar).resolve()
        if not jar.exists():
            raise SystemExit(f"本機 jar 不存在: {jar}")
        log(f"使用本機 jar: {jar}")
    else:
        info = resolve_version(version)
        vmeta = http_json(info["url"])  # version metadata JSON
        dl = vmeta["downloads"].get("server")
        if dl is None:
            raise SystemExit(f"版本 {info['id']} 沒有 server jar (可能是純用戶端版本)")
        jar = CACHE_DIR / info["id"] / "server.jar"
        if jar.exists():
            log(f"使用快取 {jar}")
        else:
            download(dl["url"], jar, dl.get("sha1"))

    # ---- 解出 bundler 內的真實核心 (versions/ + libraries/) ----
    log("解壓 bundler (跑一次 data generator, 在 build 目錄留下 versions/ 與 libraries/)")
    r = run([java, "-DbundlerMainClass=net.minecraft.data.Main", "-jar", jar, "--reports"], cwd=build_dir)
    if r.returncode != 0 and not build_dir.joinpath("versions").exists():
        sys.stderr.write(r.stdout[-2000:] + r.stderr[-2000:])
        raise SystemExit("bundler 解壓失敗 — 此版本可能太舊 (需 1.18+ bundler) 或 JDK 不符")

    runtime_jars = list(build_dir.glob("versions/*/*.jar"))
    if not runtime_jars:
        raise SystemExit(f"找不到 runtime jar ({build_dir / 'versions'})")
    runtime = runtime_jars[0]
    libs = sorted(build_dir.glob("libraries/**/*.jar"))
    if not libs:
        raise SystemExit("找不到 libraries/ — bundler 解壓不完整")
    log(f"runtime jar: {runtime} ({runtime.stat().st_size / 1e6:.1f} MB), libraries: {len(libs)} 個")

    classpath = str(runtime) + ":" + ":".join(map(str, libs))

    # ---- 編譯 + 執行 dump ----
    compile_dump(javac, "DumpCreativeTabs.java", classpath, build_dir / "classes")
    r = run([java, "-Xmx3G", "-cp", str(build_dir / "classes") + ":" + classpath,
             "DumpCreativeTabs", str(out_dir)])
    if r.returncode != 0:
        sys.stderr.write(r.stdout[-3000:] + r.stderr[-3000:])
        raise SystemExit("dump 執行失敗 (詳見 " + str(build_dir / "dump-debug.txt") + ")")

    # ---- 驗證輸出 ----
    for name in ("items.json", "creative-tabs.json"):
        p = out_dir / name
        if not p.exists():
            raise SystemExit(f"缺少輸出 {p}")
        data = json.loads(p.read_text())
        log(f"✔ {p} ({p.stat().st_size / 1e6:.2f} MB, {len(data)} 筆)")
    if not args.keep:
        shutil.rmtree(build_dir, ignore_errors=True)
    log("完成 ✅ 輸出目錄: " + str(out_dir.resolve()))


if __name__ == "__main__":
    main()