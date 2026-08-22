# 測試記錄

**設備**: Z11_SN（Android 11 / API 30 / armeabi-v7a / XTC ROM，416×468px @416dpi）
**環境**: Magisk 25210 + Riru 26.1.7
**日期**: 2026-08-08 初版；2026-08-22 補上真機驗證（7031、7032、7033、7034 四輪）

**v1.9.2.10 (7034) 已刷入並復驗**，見「7032 復驗」與「BUG-007 兩輪：7033 與 7034」。
前面幾輪的記錄原樣保留，因為每一輪都是靠上一輪暴露的東西才做的：三處傳輸缺陷在 7031
暴露、7032 消失；手錶 chrome 的兩處重疊在 7032 量到、7033 消失；而 7033 修完又暴露出
折疊工具欄標題的位置錯誤，7034 才解決。只留最後一輪就只剩結論不剩根據。

測試在 Riru flavor 上進行。Zygisk flavor 未在真機實測 —— 兩者的
`daemon.apk` / `manager.apk` / `lspd-cli` 逐字節相同（見
`scripts/verify-parity.sh`），差異僅在 native loader，因此 CLI 與管理器層面的結論
可以外推；但注入層本身仍需在 Zygisk 環境單獨驗證。

## 結果摘要

| 類別 | 數量 | 能否重跑 |
|---|---|---|
| JVM 單元測試 | 40 | 能 —— `gradlew test`，不需要設備，見下 |
| 雙 flavor 一致性斷言 | 31 | 能 —— `scripts/verify-parity.sh release` |
| 真機 CLI 驗證 | 見下面各表 | 能 —— `scripts/clitest.sh`（傳輸與併發那部分） |
| 真機 UI 驗證 | 見「真機驗證」與 [screenshots/README.md](screenshots/README.md) | 能 —— `scripts/uitest.sh` |

下面 CLI / 錯誤處理 / 持久化各表是 2026-08-08 在真機上**手工**執行的記錄，2026-08-22
在 7031 上重跑並更正了其中兩處與實際不符的地方（`list` 的模組數、回覆裡的計量單位）。

這裡原先寫的是「自動化 49 項：44 通過，5 項為輸出格式瑕疵」。倉庫裡沒有對應的測試
腳本，也沒有留下任何輸出日誌 —— 那個數字既無法重現也無法核對，寫成「自動化」會讓人
以為改了代碼跑一下就能確認沒退化，而實際上得手接一台設備重打一遍。所以改成如實標註。

兩半現在都有腳本：UI 用 `scripts/uitest.sh`，CLI 的傳輸與併發用 `scripts/clitest.sh`
（自己生成 15 KB~157 KB 四組作用域列表，不依賴任何外部文件）。仍然要手接一台設備，
所以算「可重跑」而不是「自動化」—— 沒有 CI 會替你跑它。下表的功能性條目（enable /
disable / --replace / --force-stop 這些改狀態的）仍是逐條手工執行的記錄。

同時把能脫離設備驗證的部分補成了真測試（下一節），CLI 輸入解析這塊原本最容易靜默
出錯，現在有 23 項斷言蓋住。

## JVM 單元測試

不需要設備，也不需要刷機：

```sh
./gradlew test                       # 全部
./gradlew :app:testDebugUnitTest     # 只跑管理器
./gradlew :daemon:testDebugUnitTest  # 只跑守護進程
```

失敗詳情在 `<module>/build/reports/tests/testDebugUnitTest/index.html`。

兩點容易被繞進去：`gradlew` 加了 `-q` 會把測試結果一起吞掉，只剩 javac 的提示，看着
像沒跑；`test` 會對 debug 和 release 兩個 variant 各跑一遍，所以報告裡的執行次數是下表
的兩倍（40 項 → 80 次），不是有重複測試。

| 測試 | 項數 | 覆蓋 |
|---|---|---|
| `app` `ModuleUtilTest` | 15 | 模組識別：現代/傳統標記、split apk、壞 apk、archive 是否洩漏 |
| `daemon` `CommandListenerParserTest` | 23 | CLI 輸入解析：作用域、userId、flag，以及每一種拒絕路徑 |
| `daemon` `CliRequestLimitTest` | 2 | 客戶端與守護進程的請求字節上限一致，且容得下合法的最大請求 |

選這兩處是因為它們是這輪修的兩個 bug 的**根因所在**，而且都是純函數，能脫離
Android 運行時測：

- `ModuleUtil.scanModuleApk()` 只讀 `ApplicationInfo` 的字段和 `java.util.zip`。
  BUG-001 是把「這是不是模組」和「這是什麼格式的模組」當成同一個問題，於是幾乎所有
  傳統模組都不再被識別。測試把兩者分開釘住：只有現代標記、只有傳統標記、兩個都有、
  一個都沒有，各自應該得到什麼。
- `CommandListener` 的三個解析函數是靜態的，不碰守護進程狀態。BUG-005 的兩處失敗都是
  **靜默**的：user id 打錯解析成 user 0，flag 打錯被忽略，兩種情況都照樣回 `OK`。所以
  這 23 項裡絕大多數測的是壞輸入，而不是正常路徑。

為此把 `parseScope` / `parseUserId` / `parseFlags` / `requireArg` 從 `private` 放寬到
包內可見。通過 socket 去測它們需要守護進程、`ConfigManager` 和 root —— 正是因為這樣
才一直沒被測到。

`CliRequestLimitTest` 是 7031 上機之後補的。原先兩個上限各自獨立取值：解析器收
512 個作用域目標，socket 卻只肯緩衝 8 KiB，而 512 個目標的 spec 約 15 KB —— 一條
合法命令死在傳輸層，報的是文檔裡從未出現過的 `request exceeds 8192 bytes`。現在
`MAX_REQUEST_BYTES` 由 `MAX_SCOPE_TARGETS` 推導而來，測試釘的不是那條算式，而是
「推導出的上限確實裝得下最壞情況下的合法請求」（512 個 255 字符包名 + 六位 userId +
最長的動詞和 flag，共 134938 字節，餘量 742），以及 `CliMain` 裡那份副本沒有跑偏。

測不到的部分沒有假裝測到：注入層本身需要真機，記在「未覆蓋」裡。作用域事務回滾與
`ConfigManager` 緩存刷新原先也在那一欄，2026-08-22 已在真機上驗證，見「真機驗證」。

## CLI 功能

| 測試 | 結果 |
|---|---|
| `ping` 連通性 | `OK pong` |
| `list` 列出已啟用模組 | 正確返回 4 個模組（2026-08-08 記的「2 個」是當時設備上的數量，不是上限） |
| `--help` 用法輸出 | 完整 |
| `enable` / `disable` | 狀態正確切換，`list` 即時反映 |
| `activate --scope system` | `OK activated -> 1 target(s)`，`getscope` 確認 `system/0` |
| `activate` 多作用域 | 3 個作用域全部寫入 |
| `scope` 追加 | 新增項寫入，原有項保留 |
| `activate --replace` | 舊作用域全部清除，只剩新值 |
| `unscope` 移除 | `getscope` 變空 |
| `--force-stop` | 目標應用被強制停止，無崩潰 |
| `--scope -` 空作用域 | 接受，返回 `0 target(s)` |
| 顯式 `/0` userId | 正確解析 |

回覆裡的單位 2026-08-22 從 `app(s)` 改成了 `target(s)`。原因不是措辭偏好：那個數字是
**請求裡的目標數**，不是最終留在庫裡的行數。`applyScopeBatch` 提交後 `cacheScopes()`
緊接着跑，會把當前解析不出任何進程的目標行刪掉，所以給一個未安裝的包加作用域會回報
1 個目標、實際留下 0 行。寫成 `app(s)` 會被讀成「已經 hook 了幾個應用」，那是誇大。

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
| 未安裝的作用域目標 | 計入回覆的目標數，隨後被 `cacheScopes()` 剪掉，淨效果是靜默跳過 |
| 超過 512 個目標 | `ERR ... 512 ...`，一行都不寫（7031 實測） |
| 請求超過字節上限 | 客戶端本地攔下：`error: request is N bytes, over the daemon's 135680 byte limit` + 退出 2 —— **但從 shell 打不到這條**，見「7032 復驗」 |
| 併發客戶端超過 4 個 | `ERR busy, too many concurrent cli clients`（7032 實測 10 併發 → 4 拒 6 通；7031 上這條回覆會被 RST 沖掉） |

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

## 真機驗證（2026-08-22，v1.9.2.7 / 7031）

刷入 7031 後逐條驗證了報告裡的十項。守護進程日誌不在 logcat 裡，在
`/data/adb/lspd/log/verbose_*.log`，tag `LSPosedService`（App 側是 `LSPosedManager`）。

| 項 | 驗證方式 | 結果 |
|---|---|---|
| BUG-001 描述 | 管理器模組列表逐條看 | 四個已啟用模組**全部**顯示描述，含傳統模組 CorePatch |
| BUG-001 推薦應用 | 進模組詳情 → 作用域頁 | 推薦應用既標了「推薦應用」也已預先勾選 |
| BUG-002 自身作用域 | 重複 `enable` 同一模組 | 原有 11 個目標一個沒動，無重複行 |
| BUG-003 管理器 UID | 日誌 `manager uid is 10060` | 一次解析成功，無重試 |
| BUG-004 單次重載 | 反覆下拉刷新看重載標記 | 序列化執行，無並發重載；列表未被瞬時失敗清空 |
| BUG-005 事務回滾 | 一批裡混入一個非法目標 | 整批不生效，庫中零改動 |
| BUG-005 校驗路徑 | 壞 userId / 壞 flag / 超長列表 | 全部明確拒絕，不再靜默改寫語義 |
| BUG-006 上限 | 同時起 8 個客戶端 | 上限確實生效，5 之後被拒（但見下） |
| BUG-009 版本身份 | 管理器關於頁 + `module.prop` | 一致顯示 1.9.2.7 (7031) |

BUG-003 與 BUG-004 的標記刻意放在 `Log.i`：release 構建會用 `-assumenosideeffects` 把
`Log.d`/`Log.v` 剝掉，放在 debug 級就等於在真正要發的那個構建裡看不見。

UI 這部分用 `scripts/uitest.sh` 驅動，截圖與逐張對應的結論見
[screenshots/README.md](screenshots/README.md)。

### 上機才暴露的三處缺陷（已在 7032 修掉並復驗）

前兩處是同一個根因。守護進程寫完錯誤回覆就關 socket，而客戶端往往還有幾 KB 沒寫完；
帶着未讀入站數據去 close，close 就變成 abortive —— 對端的接收緩衝區被丟棄，而剛寫進去
的那句回覆正躺在裏面。於是：

1. **超長請求**：守護進程日誌裡老老實實記着 `request exceeds 8192 bytes`，進程也健康，
   客戶端看到的卻是 `error: cannot talk to LSPosed daemon: Connection reset by peer`
   外加一句「LSPosed 是不是沒運行」。修法有兩層：`CommandListener` 在關閉前先有界地
   排空客戶端剩下的字節（`drainQuietly`），`CliMain` 再在連接前本地預檢大小 —— 本地
   錯誤能直接說清問題且不花一趟往返。
2. **併發拒絕**：第 6/7/8 個客戶端收到的是 `Broken pipe` / `Connection reset by peer`，
   而不是 BUG-006 明確要求的 `ERR busy, too many concurrent cli clients`。同一個排空
   修法覆蓋這條；同時把拒絕動作從 accept 循環移到專用單線程（`lspd-cli-rejecter`），
   否則排空會堵住 accept，「忙」就變成了「連不上」。
3. **兩個上限互相矛盾**：解析器收 512 個目標，socket 只緩衝 8 KiB，而 512 個目標約
   15 KB。現在 `MAX_REQUEST_BYTES` 由 `MAX_SCOPE_TARGETS` 推導（135680 字節，4 個並發
   客戶端峰值約 530 KiB），並由 `CliRequestLimitTest` 釘住。

三條的復驗結果在下一節。7031 那輪停在產物階段是因為當時電量 12% 且在放電（底座供數據
不供電），刷 Magisk 模組要重啟，中途斷電有寫壞模組目錄的風險 —— 換成有線 ADB 供電後
`AC powered: true / level: 21`，才動的手。

## 7032 復驗（2026-08-22，v1.9.2.8 / 7032）

刷入方式：`adb push` zip 到 `/data/local/tmp/` 並逐字節比對 sha256 → `magisk --install-module`
→ 確認暫存的 `module.prop` 是 7032 且中文沒被寫壞 → 重啟。`/proc/uptime` 74.51 證明重啟
確實發生過，不是「以為刷了」。

CLI 那三條用 `scripts/clitest.sh` 重跑（它自己在設備上用 awk 生成四組作用域列表，
15 KB~157 KB，不依賴任何外部文件）：

```sh
adb push scripts/clitest.sh /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/clitest.sh
adb shell "su -c '/data/local/tmp/clitest.sh'"          # 全部
adb shell "su -c 'BURST=10 /data/local/tmp/clitest.sh'" # 只加大併發
```

| 復驗項 | 7031 的表現 | 7032 實測 |
|---|---|---|
| 513 個目標（15902 字節） | 死在傳輸層：`request exceeds 8192 bytes` + 客戶端 `Connection reset by peer` | `ERR too many scope targets (513 > 512)` —— 走到解析器，報的是語義錯誤 |
| 130973 字節合法請求（498×255 字符 + `/100000`） | 同上，8 KiB 就斷 | 送達守護進程並得到語義回覆 |
| 131071 字節合法請求（512×255 字符，不帶 userId） | 同上 | 送達並得到語義回覆 —— 這是 argv 允許的最大值 |
| 10 個併發客戶端 | 上限生效但被拒者收到 `Broken pipe` / `Connection reset by peer` | `busy=4  reset-or-pipe=0`：4 個明確收到 `ERR busy, too many concurrent cli clients`，6 個正常服務，**零**傳輸錯誤 |
| 回歸：`ping` / `list` / `getscope` | —— | `OK pong` / 4 個模組 / 與 App 一致 |
| 回歸：重複 `scope` 冪等 | —— | `OK scoped com.AllToolBox.wear -> 3 target(s)`，作用域集合不變（順帶實地確認了 `target(s)` 這個措辭） |
| 回歸：線程回收 | —— | 測試窗口內無異常；worker 與 rejecter 線程事後都被回收，只剩 `lspd-cli-listen` |
| BUG-009 版本身份 | 1.9.2.7 (7031) | 管理器首頁 `已激活` / `1.9.2.8 (7032) - Riru` |

### 客戶端字節預檢從 shell 打不到

「請求超過字節上限」那條客戶端本地錯誤（`error: request is N bytes, over the daemon's
135680 byte limit`，退出 2）**在從 shell 調用 `lspd-cli` 時永遠不會觸發**。Linux 的
`MAX_ARG_STRLEN` 把單個 argv 字符串限制在 32 頁 = 131072 字節，低於守護進程的 135680；
而 `--scope` 收的是一個逗號分隔的參數（重複給只會覆蓋，`CliMain.java:161`），所以 600 個
目標的請求在 `exec` 之前就死在 `Argument list too long`，根本走不到那道預檢。

實際後果是**上限由 argv 而不是 `MAX_REQUEST_BYTES` 決定**：512 個目標只有在整個作用域
字符串 ≤131071 字節時才夠得到（255 字符包名不帶 userId 剛好卡在 131071），一旦每個目標
帶上 `/100000` 後綴，能打進去的最多約 498 個。預檢那段代碼不是廢的 —— 通過管道或別的
exec 路徑構造請求時它仍然是唯一的本地攔截 —— 但它防不住從 shell 來的那條路。

要讓 512 這個上限真正可達，需要 `--scope-file <path>` 或可重複追加的 `--scope`。這是新
發現的限制，不在報告的十條裡，本輪只記錄不實現。

## 狀態持久化

刷入後重啟設備，`list` 與 `getscope` 均正確返回重啟前的狀態，無數據丟失。

## 崩潰修復驗證

見 [CRASH_FIX.md](CRASH_FIX.md)。在 XTC ROM（`ShortcutManager` 返回 null）上：

- 設置頁面正常打開，不再崩潰
- 「添加快捷方式」顯示為禁用（預期行為）
- 其他設置項（通知、備份、主題、語言）正常

## 手錶適配驗證

見 [WATCH_ADAPTATION.md](WATCH_ADAPTATION.md)。**BUG-007 已在 7033 與 7034 兩輪上修完
並實測**，逐頁量了 bounds、留了截圖，過程與數字見「BUG-007 兩輪：7033 與 7034」。

- 手機回歸：`isWatch()` 為 false，`ThemeOverlay.Watch` 不套用、`compactNavForWatch()`
  立即 return，底部導航與尺寸與原版一致
- 手錶上原先**能用但不好用**，2026-08-22 在 7032 上量到兩處具體證據（下面兩段原樣保留，
  它們是 7033/7034 的對照基線）

**模組詳情頁：齒輪壓住開關（7032）。** `uiautomator dump` 量到的邊界：

```
啟用模組  TextView      [63,236][217,283]
Switch                  [259,218][353,301]
模組設置  ImageButton   [298,211][395,308]   <- 與 Switch 橫向重疊 298..353（55px ≈ 21dp）
```

`Switch` 沒被壓住的部分只剩 `259..297`，38px ≈ 15dp，而且**滑塊本身整個藏在齒輪下面**，
截圖裡看不出模組是開還是關。點 (340,260) 打開的是模組自己的設置頁，模組狀態沒變 ——
重疊區的觸摸被後畫的 ImageButton 吃掉了。證據：`docs/screenshots/10-module-detail-switch-occluded-7032.png`
與 `11-module-settings-wins-overlap-7032.png`。

**列表頁：內容區只剩 ~163px（7032）。** app bar 166px + 底部導航 139px，468px 高的屏幕
留給內容的不到三分之一，所以模組列表一屏只看得見一行多。這也是為什麼上一輪「WeichatPro2
沒有描述」是誤判 —— 描述在摺疊線以下，再滾一下就出來了（`[146,269][374,329]`）。

### 一條記錯了的前提：`-watch` 限定符在這台設備上選不中

原先這裡寫的是「APK 中確認包含 `res/layout-watch-v20/activity_main.xml`」，後來改成
「真正在 release 裡活下來的是資源表裡的 `watch` 限定符」。**兩句都對，但都不足以說明
運行時會選中它 —— 而它根本不會被選中。**

`-watch` 匹配的是 configuration 的 `uiMode = UI_MODE_TYPE_WATCH`，不是
`android.hardware.type.watch` 這個 hardware feature。這台設備兩者不一致：

```sh
$ adb shell pm list features | grep watch
feature:android.hardware.type.watch          # 聲明了
$ adb shell am get-config
config: zh-rCN-...-notround-...-416dpi-...-468x416-v30    # 整串沒有 watch token
$ adb shell dumpsys uimode | grep mCurUiMode
  mCurUiMode=0x0                             # UI_MODE_TYPE_NORMAL，不是 0x6
```

所以 `layout-watch/` 與 `values-watch/` 在這台設備上是死代碼，運行時 inflate 的是手機
佈局。`verify-parity.sh` 那條斷言仍然有效，只是它證明的是「打進了 APK」，不是「會被選中」
—— 這兩件事之前被當成一件。

實際生效的兩條路徑是 `ThemeOverlay.Watch`（主題屬性）與按 `BaseActivity.isWatch()`
分支的代碼，`isWatch()` 讀的正是這台設備確實上報的那個 hardware feature。
`.claude/plans/parsed-sparking-rocket.md` 的第 1~4 步全部建在 `values-watch/` /
`layout-watch/` 上，**機制上已作廢**，7033/7034 走的是上面那兩條。

## BUG-007 兩輪：7033 與 7034

### 7033：壓縮 chrome

改動只在管理器側，三處：`ThemeOverlay.Watch` 把 `collapsingToolbarLayoutLargeSize`
從 152dp 降到 56dp；`MainActivity.compactNavForWatch()` 把底部導航壓到 48dp、圖標 20dp、
標籤設為 `LABEL_VISIBILITY_UNLABELED`；`AppListFragment` 把模組詳情頁的 fab 改
`SIZE_MINI`。

| 量 | 7032 | 7033 |
|---|---|---|
| app bar | 166px | 97px（56dp） |
| 內容區 | 163px | 288px |
| 底部導航 | 139px | 83px |
| 導航 item | 標籤畫在 468px 屏幕的底邊之外 | 83×83px = 47.9dp，≥ Material 48dp 觸控最小值 |
| 詳情頁開關 vs fab | 橫向重疊 55px | 縱向間隔 49px |

`LABEL_VISIBILITY_UNLABELED` 是必須的：五個 item 在默認的 `AUTO` 下只給選中項畫標籤，
那一個標籤就是被畫到屏幕外的那個（原先靠 `layout-watch/` 的
`app:labelVisibilityMode="unlabeled"`，而它選不中）。

順帶修掉兩個先前沒記錄的缺陷：概覽頁第二張卡片原先只有 1px 高（`API 版本` 那張）；
底部導航標籤原先落在 y=439..468。

**功能性驗證，不只是幾何**（7034 上重跑，結論相同）：點開關 (306,190) → `lspd-cli list`
裡 `com.AllToolBox.wear` 消失；再點一次 → 回來；`getscope` 三個目標
（`com.AllToolBox.wear/0`、`com.xtc.i3launcher/0`、`system/0`）一個沒少。焦點全程留在
管理器，沒有跳進模組自己的設置頁 —— 7032 上點同一個位置跳的就是那裡。

### 7034：折疊工具欄的標題幾何

7033 上機後暴露一處新缺陷：**標題被屏幕頂邊裁掉**（`docs/screenshots/14` 左上角）。
7032 上同一個標題是被返回箭頭壓住（`docs/screenshots/10`）。

之前這裡預測過「56dp = actionBarSize，所以大標題折疊成普通工具欄，標題與返回鍵的碰撞
一併解決」。**這個預測是錯的**，真機上碰撞消失了但標題改為在頂邊裁切。根因不是高度：

- 折疊工具欄的總高（56dp）等於裏面 pin 住的 `MaterialToolbar` 的高（`actionBarSize`
  也是 56dp），所以可滾動範圍是 0，`expandedFraction` 永遠是 1 —— 它**卡在 expanded
  出不來**
- 於是只有 expanded 那組幾何值會被用到，而 Material `.Large` 的 expanded 是照 152dp 盒子
  算的（28sp 兩行標題 + 對應 margin）。96dp 盒子裏它壓住返回鍵，56dp 盒子裏它畫到頂邊外
- 兩種都不是高度問題，改高度都治不了；而 collapsed 那組幾何本來是對的（collapsed bounds
  取自 Toolbar 裏的 dummyView，天然避開返回鍵與右側圖標），只是永遠用不上

修法是 `Widget.Watch.CollapsingToolbar`（`values/themes_overlay.xml`，經
`collapsingToolbarLayoutLargeStyle` 掛到 `ThemeOverlay.Watch`）：把 expanded 的字號與
margin 改寫成 collapsed 的樣子 —— `textAppearanceTitleMedium` / `textAppearanceBodySmall`，
橫向 gutter 用實測值（返回箭頭 56dp、搜索加溢出菜單 88dp，中間 96dp 給標題）。

**判定只能靠截圖。** app bar 裏沒有 TextView 子節點 —— 標題和副標題都是
`SubtitleCollapsingToolbarLayout` 畫在 canvas 上的，`uiautomator dump` 只給得出 app bar
的 `content-desc`，看不出畫在哪、有沒有被裁。這個觀察也正是當初斷定「是 CTL 而不是
Toolbar 在畫這兩行」的依據，因而排除了 `titleEnabled=false`（那會讓 app bar 一個字都
不剩）。

7034 實測（`docs/screenshots/16`~`20`）：

| 檢查 | 結果 |
|---|---|
| 詳情頁標題 | `AllTo…` 與返回箭頭同排不重疊，副標題 `com.AllT…` 在其下，兩行都在 97px 之內 |
| 五個標籤頁 app bar | 全部 `[0,0][416,97]`，內容全部從 y=97 起 |
| 詳情頁開關 / fab | `[259,149][353,232]` chk=true / `[312,281][395,364]`，間隔 49px |
| 崩潰 | `logcat -b crash` 裏沒有任何管理器 / 守護進程的記錄（唯一的內容是開機瞬間 19:17:38~45 三次 `adbd` SIGABRT，是 adb 傳輸從無線切到有線時 adbd 自己重啟，與本模組無關） |
| 版本身份 | 概覽頁與 `module.prop` 一致顯示 `1.9.2.10 (7034)`；守護進程自己報 7034，所以刷的是整個模組不是只換 manager.apk |
| 產物一致 | zip 內 `manager.apk` 與 `/data/adb/modules/riru_lsposed/manager.apk` sha256 相同（`a98ad394…`） |

**仍未解決（下一輪）**：設置頁的 preference 行擁擠 —— 圖標與開關各佔一側 gutter 後標題
只剩約 90dp，「安全 DNS（DoH）」折兩行、摘要折三行被裁（`docs/screenshots/19`）。這是
內容密度不是 chrome，可以滾動，量級與前面兩處不同。

### 7033 的產物身份出過一次岔子

7034 的第一個構建**覆蓋掉了 `magisk-loader/release/` 裡已經刷進設備的 7033 zip** ——
改完資源直接跑構建，而版本號還是 7033。這正是 BUG-009 說的那種歧義，只是發生在小處：
兩份不同的字節都自稱 7033。

處理：設備上 `/data/local/tmp/lsposed-7033.zip` 還在，pull 回來存成
`magisk-loader/release-archive-7033/…as-flashed.zip`，並用它內部 `manager.apk` 的 sha256
（`92f803c9…`）與 `/data/adb/modules/riru_lsposed/manager.apk` 對上，證明它就是跑出那些
7033 結論的那份字節。覆蓋它的那個構建改名為 `…rebuilt-never-flashed.zip` 一併留檔，
理由寫在同目錄的 `NOTE.txt`。zygisk 那一份不可恢復（從未 push 到設備），但 7033 上沒有
任何結論依賴 zygisk 字節。然後 `forkPatch` 從 9 進到 10，7034 才是帶標題修正的那個版本。

教訓很直接：**改完就構建之前先看版本號**。7033 已經刷出去了，它的字節就有主了。

## 截圖證據

有效的截圖在 `docs/screenshots/`，十九張（`01`~`07` 來自 7031，`08`~`11` 來自 7032，
`12`/`14`/`15` 來自 7033，`16`~`20` 來自 7034），每張對應一條結論，索引見
[screenshots/README.md](screenshots/README.md)。`13` 缺號 —— 那張截到的是詳情頁而不是
它名字說的模組列表，見下。

倉庫根目錄原先那五張**不能當證據用**，三種毛病各佔一個。它們沒有刪掉，移到了
`docs/screenshots/rejected/`（保留而不是刪掉：每一份都是 `capture-screen.ps1` 某一條
校驗規則的來源樣本，規則是拿它們對出來的，刪了就只剩規則沒有依據）：

| 文件 | 狀況 |
|---|---|
| `manager-device-test.png` | 不是 PNG。開頭是 UTF-8 BOM，`0x89` 變成了 U+FFFD |
| `verify-modules.png` | 0 字節 |
| `modules-screen.png` / `modules-current.png` / `modules-after-update.png` | 三個文件 sha256 完全相同 —— 同一張圖存了三個名字，卻被當成三個不同狀態記錄 |
| `13-modules-two-full-rows-7033-WRONG-SCREEN.png` | 合格的 PNG，但截到的不是名字說的那一頁：想回列表用的是「再點一次 Modules 標籤」，而 Navigation 的 multiple back stacks 恢復了該標籤保存的目標，於是又回到詳情頁。名實不符，與上面三張同類，按同樣規矩處理；它本該證明的東西由 `17` 補上 |

第一個是 PowerShell 的坑：`adb exec-out screencap -p > out.png` 在 PowerShell 裡不成立。
PowerShell 會先用 `[Console]::OutputEncoding` 把原生命令的 stdout 解碼成字符串，重定向
拿到的已經是文本，於是每個不合法的字節都變成 U+FFFD 並補上 BOM。得到的文件大小看着
正常（那個壞文件有 39 KB），但字節已經丟了，**不可恢復**。Git Bash 下同樣的重定向是
二進制安全的，所以同一條命令換個 shell 就對了 —— 這也是它容易被寫進文檔的原因。

修不回來的就不修了，改成讓它不會再發生：

```powershell
.\scripts\capture-screen.ps1 -Name modules
.\scripts\capture-screen.ps1 -Validate .\modules-screen.png   # 檢查已有文件
```

腳本在設備上 `screencap` 再 `adb pull`，字節完全不經過 PowerShell 的管道；拉下來後先
校驗 PNG 簽名、走完 chunk 鏈確認有 IEND、讀出真實尺寸，不合格就**刪掉**而不是留在那裡
充當證據；文件名帶時間戳，並且會拿 sha256 跟同目錄已有截圖比對，發現字節相同就明確
指出跟哪個文件重了。

三類毛病都用它在現有文件上驗證過：壞頭報 `not a PNG (header mangled ...)` 退出 1，
空文件報 `the file is empty` 退出 1，三張重複的互相指認。現在 `docs/screenshots/` 裡的
十九張全部通過校驗，尺寸都是 416×468，互不重複 —— 7032 那輪第一次 `screencap` 拿到的
是個 0 字節文件（鎖屏還沒解開），正是這個校驗器攔下來的那一類，重採後才入庫。

校驗器攔不住的是**名實不符**：`13` 是個尺寸正確、sha256 也不重複的合格 PNG，只是拍錯了
頁面。這類只能靠採集時當場核對 —— 現在的做法是每張截圖都配一份同時刻的 `uiautomator
dump`（存在 `.device-snapshots/`），bounds 對不上就是拍錯了。

## 未覆蓋

需要設備，尚未做：

- BUG-003 的「PackageManager 未就緒時啟動」與「卸載/重裝」時序 —— 正常啟動路徑已覆蓋，
  這兩個要製造時序
- BUG-004 的加載失敗提示 —— 要能製造一次 Binder 瞬時失敗
- Zygisk flavor 的注入層真機測試
- **設置頁 preference 行的密度** —— BUG-007 的 chrome 部分已修完並實測（7033/7034），
  剩下的是內容密度：圖標與開關各佔一側 gutter 後標題只剩約 90dp，「安全 DNS（DoH）」折兩行、
  摘要折三行被裁（`docs/screenshots/19`）。可以滾動，不擋操作，量級低於已修的那兩處
- **真正上報 `uiMode=watch` 的設備** —— `values-watch/` 那一組值（`app_icon_size` 24dp、
  `tab_layout_height` 32dp、`home_primary_elevation` 1dp）在參考設備上選不中，從未生效過，
  也照 159dp 視口寫的而不是實際的 240dp 視口，很可能偏小。見
  「一條記錯了的前提」與 [WATCH_ADAPTATION.md](WATCH_ADAPTATION.md#尺寸)
- 圓形錶盤（參考設備是方屏，`am get-config` 報 `notround`）

以下與設備無關但仍未做：

- 多用戶 / 分身環境下的跨用戶操作
- Android 12+ （測試設備為 Android 11）
- **讓 512 個作用域目標真正可達** —— 現在被 argv 的 131072 字節卡在約 498 個，
  需要 `--scope-file` 或可重複的 `--scope`，見「客戶端字節預檢從 shell 打不到」
- `readRequest` 是**逐字節**從無緩衝的 `LocalSocket` 讀的，一個頂到上限的請求要
  ~131k 次 read 系統調用；7032 把上限從 8 KiB 提到 135680 之後這個代價漲了 16 倍。
  功能上沒問題（併發上限是 4，5 秒超時也夠），但套一層 `BufferedInputStream` 是幾乎
  零風險的改動，留作後續

一致性校驗腳本默認跑 debug：`scripts/verify-parity.sh` 不帶參數時找的是 debug zip，
本機只有 release 產物，所以會以退出碼 2 報「缺少產物」而不是報不一致。跑
`scripts/verify-parity.sh release`。它也會在 `magisk-loader/release/` 同時存在多個版本
時退出 2（「multiple release zips present」）而不是任選一個 —— 舊版本挪到
`magisk-loader/release-archive-<verCode>/`，7031 就在那裡。
