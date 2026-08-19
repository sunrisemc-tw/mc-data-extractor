# mc-data-extractor

輸入 Minecraft 版本 → 輸出該版本的**全部物品清單**與**原版創造模式分頁**為格式化 JSON。
（伺服器內建資料與創造分頁是程式碼寫死的，data generator 不會輸出；本工具直接 bootstrap 遊戲核心後從記憶體抽取，100% 吻合正式服。）


## 用法

```bash
# 自動下載官方 server jar 並提取（預設含繁體中文翻譯 name_zh_tw / display_name_zh_tw）
python3 extract.py 1.26.2

# 用本機 jar（vanilla / Paper / Canvas 任何 bundler 皆可）
python3 extract.py 1.26.2 --jar ~/Downloads/paper-26.2-21.jar

# 指定輸出目錄 / 保留中間檔 / 換語言（en_us、zh_cn、ja_jp...任一官方語系）
python3 extract.py 1.26.2 -o ~/Desktop/dump --keep --lang zh_cn

# 不需要翻譯欄位
python3 extract.py 1.26.2 --no-lang

# 查看 Mojang 可用版本
python3 extract.py --list
```

需要 Python 3.8+ 與 JDK 25+（自動偵測系統最新 JDK）。server jar 會快取在
`~/.cache/mc-data-extractor/`，重跑不用重下。


## 輸出

`output/<版本>/`：

### items.json — 全部物品清單（1.26.2 共 1537 筆）

```json
{
  "id": "minecraft:stone_hoe",
  "protocol_id": 953,
  "translation_key": "item.minecraft.stone_hoe",
  "name_zh_tw": "石鋤",
  "is_block": false,
  "max_stack_size": 1,
  "rarity": "common",
  "max_damage": 131
}
```

> 翻譯預設為繁體中文（`name_zh_tw`），可換成任一台版官方語系（`--lang`）。
> 翻譯來源為官方 asset index 的 `minecraft/lang/<lang>.json`，與遊戲內顯示完全一致。

### creative-tabs.json — 原版創造模式（1.26.2 共 14 個 tab）

```json
{
  "id": "minecraft:combat",
  "display_name": "Combat",
  "display_name_zh_tw": "戰鬥",
  "type": "CATEGORY",
  "icon": "minecraft:netherite_sword",
  "items": ["minecraft:netherite_sword", "..."],
  "stacks": [
    {"id": "minecraft:painting", "components": {"minecraft:painting/variant": "minecraft:alban"}}
  ],
  "search_items": [...],
  "search_stacks": [...]
}
```

- `items` / `search_items`：**精確的分頁格子順序**（ID 清單）
- `stacks` / `search_stacks`：與 items 平行的完整資料，含非預設 components —
  畫作 52 種變體、旗幟圖案、藥水、附魔書等 NBT 細節全部保留，可 100% 復刻
- `type` 欄位：`CATEGORY` 一般分頁 / `SEARCH` 搜尋頁 / `HOTBAR`、`INVENTORY`（無內容，玩家專用）
- `op_blocks` 分頁需創造+權限（`tryRebuildTabContents` 以 hasPermissions=true 建構）


## 原理

1. 下載 Bundler server jar（或使用本機 jar）
2. 跑一次 `java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports`
   讓 bundler 解出真實核心 `versions/<ver>/<name>.jar` + `libraries/`（官方 data generator 路徑，不會啟動伺服器）
3. 用該核心完整 bootstrap：`Bootstrap.bootStrap()` → 載入 jar 內建 datapack →
   `TagLoader` 綁定全部 tags → `RegistryDataLoader` 載入 worldgen 動態 registry →
   合成完整 RegistryAccess → `DataComponentInitializers` 綁定 components →
   `CreativeModeTabs.tryRebuildTabContents()` 重建分頁
4. 直接 dump `getDisplayItems()` / `getSearchTabDisplayItems()` 與物品 registry


## 檔案

| 檔案 | 說明 |
|---|---|
| `extract.py` | 主程式（版本次數化 CLI） |
| `DumpCreativeTabs.java` | 遊戲核心內執行抽取的 Java 程式 |