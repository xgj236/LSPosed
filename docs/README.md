# 文檔

本 fork 相對上游 LSPosed 的改動，以及構建方式。

| 文檔 | 內容 |
|---|---|
| [BUILDING.md](BUILDING.md) | 構建腳本用法、環境要求、libxposed 依賴的提交選點陷阱 |
| [CLI.md](CLI.md) | `lspd-cli` 命令行激活模組 / 勾選作用域：原理、用法、線協議、權限模型 |
| [CRASH_FIX.md](CRASH_FIX.md) | 定製 ROM 上 `ShortcutManager` 為 null 導致設置頁崩潰的修復 |
| [WATCH_ADAPTATION.md](WATCH_ADAPTATION.md) | 智能手錶小屏適配（`layout-watch/` + `values-watch/`） |
| [TESTING.md](TESTING.md) | 單元測試怎麼跑、真機測試記錄、已知未覆蓋範圍 |

## 腳本

| 腳本 | 用途 |
|---|---|
| `scripts/build.sh` / `build.ps1` | 一鍵構建兩個 flavor，見 [BUILDING.md](BUILDING.md) |
| `scripts/verify-parity.sh` | 斷言兩個 flavor 一致、版本號對得上（默認 debug，本機產物是 release） |
| `scripts/release-manifest.sh` | 記錄每次發布的版本、源碼 commit、產物 sha256 與簽名指紋 |
| `scripts/capture-screen.ps1` | 採集設備截圖並校驗 PNG 完整性，見 [TESTING.md](TESTING.md#截圖證據) |

單元測試不需要設備：`./gradlew test`。

## 改動概覽

三處改動，互不依賴：

**CLI（`lspd-cli`）** — 守護進程新增一個抽象 socket `@lspd_ctl` 上的單行文本控制通道，
可在 root shell 裡一條命令完成「啟用模組 + 勾選作用域」，走的是與管理器 App
完全相同的 `ConfigManager` 接口。調用方 uid 限制為 0（root）與 2000（adb shell），
讀不到憑據時拒絕服務。

**崩潰修復** — `ShortcutUtil` 的 4 處 `getSystemService(ShortcutManager.class)`
增加 null 檢查。部分定製 ROM（如測試設備的 XTC ROM）移除了該服務，原代碼會
在打開設置頁時必然 NPE。

**手錶適配** — 走 `ThemeOverlay.Watch`（`values/themes_overlay.xml`，在 inflate 之前套用）
加上按 `BaseActivity.isWatch()` 分支的代碼（`MainActivity.compactNavForWatch()`、
`AppListFragment` 的 mini fab）。倉庫裏也有 `layout-watch/` 與 `values-watch/`，但**在參考
設備上永遠選不中** —— `-watch` 匹配的是 `uiMode`，不是 `android.hardware.type.watch` 這個
feature，而這台設備聲明了 feature、`uiMode` 卻是 NORMAL。詳見
[WATCH_ADAPTATION.md](WATCH_ADAPTATION.md)。

## Riru / Zygisk 一致性

`app`（管理器）與 `daemon` 模塊不區分 flavor，上述三處改動對兩個 flavor 完全等效 ——
兩個 zip 裡的 `manager.apk`、`daemon.apk`、`lspd-cli` 應當逐字節相同，只有 native
loader 與 flavor 標記不同。

這一點需要主動維護：單獨構建一個 flavor、改代碼、再單獨構建另一個，會讓兩個 zip
來自不同源碼狀態。`scripts/build.sh` 因此默認一次構建兩個 flavor，並在構建後運行
`scripts/verify-parity.sh` 斷言一致性。
