# 測試記錄

**設備**: Z11_SN（Android 11 / API 30 / armeabi-v7a / XTC ROM）
**環境**: Magisk 25210 + Riru 26.1.7
**日期**: 2026-08-08

測試在 Riru flavor 上進行。Zygisk flavor 未在真機實測 —— 兩者的
`daemon.apk` / `manager.apk` / `lspd-cli` 逐字節相同（見
`scripts/verify-parity.sh`），差異僅在 native loader，因此 CLI 與管理器層面的結論
可以外推；但注入層本身仍需在 Zygisk 環境單獨驗證。

## 結果摘要

自動化 49 項：44 通過，5 項為輸出格式瑕疵（見下），無功能性失敗。
手動 12 項：全部通過。

## CLI 功能

| 測試 | 結果 |
|---|---|
| `ping` 連通性 | `OK pong` |
| `list` 列出已啟用模組 | 正確返回 2 個模組 |
| `--help` 用法輸出 | 完整 |
| `enable` / `disable` | 狀態正確切換，`list` 即時反映 |
| `activate --scope system` | `OK activated -> 1 app(s)`，`getscope` 確認 `system/0` |
| `activate` 多作用域 | 3 個作用域全部寫入 |
| `scope` 追加 | 新增項寫入，原有項保留 |
| `activate --replace` | 舊作用域全部清除，只剩新值 |
| `unscope` 移除 | `getscope` 變空 |
| `--force-stop` | 目標應用被強制停止，無崩潰 |
| `--scope -` 空作用域 | 接受，返回 `0 app(s)` |
| 顯式 `/0` userId | 正確解析 |

作用域累加與替換的語義驗證：

```
初始: 空
scope manager              → [manager]
scope systemui,system      → [manager, systemui, system]
activate --replace system  → [system]          # 舊值被清除
unscope system             → []
```

## 錯誤處理

| 場景 | 返回 |
|---|---|
| `enable` 不存在的模組 | `ERR cannot enable ... (not installed or not an Xposed module?)` |
| `activate` 非 Xposed 應用 | 同上，拒絕 |
| `--scope system/10` | `ERR ... the system scope is only valid for user 0` |
| `getscope` 不存在的模組 | `OK` + 空列表 |
| 未安裝的作用域目標 | 靜默跳過，不影響其他作用域寫入 |

退出碼：`0` 成功 / `1` 服務端錯誤 / `2` 參數錯誤 —— 實測與預期一致，可用於腳本。

### 已知的格式不一致（5 項，不修）

客戶端參數校驗輸出 `error:` 到 stderr 並退出 2，服務端錯誤輸出 `ERR` 到 stdout
並退出 1。自動化測試把這 5 項標為失敗，因為它們期望統一的 `ERR` 前綴。

保持現狀是有意的：兩類錯誤語義不同（本地用法錯誤 vs 遠端執行失敗），分開標識讓
調用方能區分「我命令寫錯了」和「守護進程拒絕了」。退出碼已經足夠腳本化，
統一前綴反而會丟失這個區分。

## 與管理器 App 的一致性

CLI 與管理器共用 `/data/adb/lspd/config/modules_config.db`：

- `list` 輸出與 App 的模組列表一致
- `getscope` 輸出與 App 中勾選的作用域一致
- CLI 寫入後 App 無需重啟即可看到變更（`ConfigManager` 同時刷新緩存）

新勾選的作用域對目標應用**之後新建的進程**生效；已運行的進程需重啟或用
`--force-stop`。這與管理器 App 的行為相同。

## 狀態持久化

刷入後重啟設備，`list` 與 `getscope` 均正確返回重啟前的狀態，無數據丟失。

## 崩潰修復驗證

見 [CRASH_FIX.md](CRASH_FIX.md)。在 XTC ROM（`ShortcutManager` 返回 null）上：

- 設置頁面正常打開，不再崩潰
- 「添加快捷方式」顯示為禁用（預期行為）
- 其他設置項（通知、備份、主題、語言）正常

## 手錶適配驗證

見 [WATCH_ADAPTATION.md](WATCH_ADAPTATION.md)。

- APK 中確認包含 `res/layout-watch-v20/activity_main.xml`
- 手機回歸：底部導航與尺寸與原版一致
- **未在真實手錶或 Wear OS 模擬器上實測**

## 未覆蓋

- Zygisk flavor 的注入層真機測試
- 多用戶 / 分身環境下的跨用戶操作
- Android 12+ （測試設備為 Android 11）
- 併發調用 CLI（監聽器單線程順序處理，未做壓力測試）
