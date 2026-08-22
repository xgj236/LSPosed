# 截圖證據（2026-08-22）

`01`~`07` 採自 **v1.9.2.7 (7031)**，`08`~`11` 採自 **v1.9.2.8 (7032)**，
`12`~`15` 採自 **v1.9.2.9 (7033)**，`16`~`20` 採自 **v1.9.2.10 (7034)** —— 同日逐輪刷入後重採。
`13` 缺號，原因見文末 rejected/。

設備 Z11_SN（Android 11，416×468px @416dpi），Riru flavor，刷入 7031 後重啟採集。
全部經 `scripts/capture-screen.ps1 -Validate` 檢驗：PNG 簽名與 chunk 鏈完整、尺寸
416×468、互不重複。

| 文件 | 畫面 | 它證明什麼 |
|---|---|---|
| `01-home-activated-7031.png` | 概覽頁 | `已激活 / 1.9.2.7 (7031) - Riru`；模組角標 4，與 CLI `list` 一致（BUG-009） |
| `02-modules-legacy-description.png` | 模組列表第 1 屏 | `核心破解 4.8` 帶出描述 `Android 9-16 核心破解` —— 傳統模組的描述回來了（BUG-001） |
| `03-modules-list-scrolled-1.png` | 模組列表第 2 屏 | `AllToolBox手表版 - 功能工具箱` |
| `04-modules-list-scrolled-2.png` | 模組列表第 3 屏 | `為系統帶來更出色的體驗`、`WeichatPro2 1.9.7 / 重構全新版…` |
| `05-module-detail-enable-switch.png` | 模組詳情頁 | `啟用模組` 開關為開；FAB 壓在卡片上（BUG-007 實測現象，見下） |
| `06-scope-recommended-app.png` | 作用域列表 | `系統框架 (system)` 已勾選並標註 **推薦應用** —— 推薦作用域回來了（BUG-001 後半） |
| `07-scope-list-continued.png` | 作用域列表續 | 推薦項置頂並標註，其餘項未勾選，長包名省略正常 |

四個已啟用模組**全部**有描述，不是抽樣：`核心破解`（傳統模組，BUG-001 的典型受害者）、
`AllToolBox手表版`、`系統增強`、`WeichatPro2`。

`06` 裡的 `system/0` 與 CLI `getscope com.coderstory.toolkit` 的輸出逐項相同，所以
「CLI 與管理器共用同一份狀態」這條在 7031 上是實測過的，不是推論。

## v1.9.2.8 (7032) 刷入後補採

7032 修的是 7031 上機才暴露的三處 CLI 傳輸缺陷，UI 層沒有改動，所以這四張的作用是
**確認刷入生效**加上**把 BUG-007 的一處現象量準**。

| 文件 | 畫面 | 它證明什麼 |
|---|---|---|
| `08-home-activated-7032.png` | 概覽頁 | `已激活 / 1.9.2.8 (7032) - Riru` —— 刷入生效，版本身份跟着走（BUG-009） |
| `09-modules-legacy-description-7032.png` | 模組列表第 1 屏 | `核心破解 4.8` 仍帶描述 `Android 9-16 核心破解` —— 7032 沒有回退 BUG-001 |
| `10-module-detail-switch-occluded-7032.png` | 模組詳情頁 | 齒輪按鈕把「啟用模組」開關的**滑塊整個蓋住**，開關狀態不可見（BUG-007） |
| `11-module-settings-wins-overlap-7032.png` | 模組自身設置頁 | 點在重疊帶上（x=340, y=260）進的是模組設置，不是開關 —— 重疊處是按鈕吃到事件 |

`10` 這一張把原先 `05` 只能定性描述的「FAB 壓在卡片上」量成了具體數字，`uiautomator dump`
的 bounds 是：

```
啟用模組  TextView      [63,236][217,283]
Switch                  [259,218][353,301]
模組設置  ImageButton   [298,211][395,308]   <- 與 Switch 橫向重疊 298..353（55px ≈ 21dp）
```

開關可點的只剩左邊 `259..297`（38px ≈ 15dp），而且滑塊在齒輪底下，**看不出開還是關**。
`11` 是驗證誰吃到點擊的結果：按鈕在上。所以 BUG-007 在這一頁要解的不是「FAB 有點擠」，
而是「開關的狀態指示和一半觸控區被另一個控件佔了」。

順帶記一個同屏現象：詳情頁標題 `Weic...` 被返回箭頭壓住（見 `10` 左上角），
`com.hua...` 也被截斷 —— 摺疊工具欄在 159dp 寬下沒有給返回鍵留位置。

## v1.9.2.9 (7033)：BUG-007 第一輪 —— chrome 壓縮

7033 把 app bar 從 152dp 的 large 折疊工具欄壓到 56dp、底部導航從 80dp 壓到 48dp，
並把模組詳情頁的 fab 改成 mini。這四張是那一輪的結果。

| 文件 | 畫面 | 它證明什麼 |
|---|---|---|
| `12-home-activated-7033.png` | 概覽頁 | `已激活 / 1.9.2.9 (7033) - Riru`；兩張卡片都有正常高度 —— 7032 上第二張卡片只有 1px 高 |
| `14-module-detail-switch-clear-7033.png` | 模組詳情頁 | 「啟用模組」整張卡片完整可見，滑塊明確為開，齒輪遠在下方 —— 7032 的 55px 重疊消失了。**同時暴露了新缺陷**：左上角標題被屏幕頂邊裁掉 |
| `15-fab-hidden-on-scroll-7033.png` | 模組詳情頁滾動後 | fab 隨滾動隱去（`hide_bottom_view_on_scroll_behavior`），所以它與下方複選框的常規重疊不是死角 |

`14` 的 bounds（對比 7032 見 `10`）：

```
啟用模組  TextView      [63,167][217,214]
Switch                  [259,149][353,232]   <- 完整可見，chk=true
模組設置  ImageButton   [312,281][395,364]   <- 縱向間隔 49px，不再重疊
```

7033 的 chrome 數字：app bar 166→97px、內容區 163→288px、底部導航 139→83px。
底部導航每個 item 83×83px = 47.9dp，仍在 Material 48dp 觸控最小值上。

## v1.9.2.10 (7034)：BUG-007 第二輪 —— 標題幾何

7033 暴露的標題裁切不是高度問題：折疊工具欄的總高等於裏面 pin 住的 Toolbar 的高，
可滾動範圍是 0，於是它卡在 expanded 狀態出不來，而 Material 的 expanded 幾何是照 152dp
的盒子算的。7034 用 `Widget.Watch.CollapsingToolbar` 把 expanded 的字號與 margin 改寫成
collapsed 的樣子，詳見 [WATCH_ADAPTATION.md](../WATCH_ADAPTATION.md#折疊工具欄它在這塊屏幕上永遠展不開也收不起)。

| 文件 | 畫面 | 它證明什麼 |
|---|---|---|
| `16-module-detail-title-fixed-7034.png` | 模組詳情頁 | `AllTo…` 與返回箭頭同排且不重疊，副標題 `com.AllT…` 在其下，兩行都在 56dp 的 app bar 之內 —— 對比 `14` 的裁切與 `10` 的壓字。開關仍完整可見、fab 仍在下方 |
| `17-modules-two-full-rows-7034.png` | 模組列表 | 標題 `模块` + 副標題 `已启用 4 个模块` 完整；一屏兩整行模組（`核心破解 4.8` 帶描述、`AllToolBox手表版 1.0` 帶描述）—— 7032 上一屏只看得見一行多 |
| `18-logs-tabs-and-content-7034.png` | 日誌頁 | 56dp app bar + 48dp tab 條之後仍有六行日誌可見；`模块日志 / 详细日志` 兩個 tab 都可點 |
| `19-settings-title-fixed-density-open-7034.png` | 設置頁 | 標題與副標題 `1.9.2.10 (7034) -…` 正常；**同時記錄仍未解決的密度問題** —— 「安全 DNS（DoH）」折成兩行、摘要折三行被裁 |
| `20-home-activated-7034.png` | 概覽頁 | `已激活 / 1.9.2.10 (7034) - Riru` —— 守護進程自己報 7034，證明刷的是整個模組而不是只換了 manager.apk |

`16` 的 app bar 佈局（`uiautomator dump`）：

```
app bar               [0,0][416,97]     desc=AllToolBox手表版
返回箭頭 ImageButton  [0,0][97,90]      <- 標題盒子從 x=97 起（56dp gutter）
搜索     Button       [264,0][348,83]   <- 標題盒子到 x=264 止（88dp gutter）
溢出菜單 ImageView    [348,0][416,83]
內容區   RecyclerView [0,97][416,385]
```

五個標籤頁的 app bar 全部是 `[0,0][416,97]`，內容全部從 y=97 起，`logcat -b crash` 無記錄。
標題完整顯示這件事只能靠截圖判定 —— app bar 裏沒有 TextView 子節點，標題和副標題都是
`SubtitleCollapsingToolbarLayout` 畫在 canvas 上的，`uiautomator` 只能給出 app bar 的
`content-desc`，看不出畫在哪裏、有沒有被裁。這也是當初判斷「是誰在畫這兩行字」的依據。

## rejected/

`rejected/` 裡是**不能當證據用**的文件。它們被移到這裡而不是刪除，因為每一份都是
`capture-screen.ps1` 某一條校驗規則的來源樣本 —— 那些規則是拿它們對出來的，刪了就
只剩規則沒有依據。

2026-08-17 留下的五個（壞 PNG 頭、0 字節、三個同 sha256 的重複文件），原因見
[TESTING.md](../TESTING.md#截圖證據)，`01`~`07` 是它們的替代品。

外加一個 2026-08-22 的：`13-modules-two-full-rows-7033-WRONG-SCREEN.png`。它是個合格的
PNG，問題在於**截到的不是它名字說的那一頁** —— 當時想回模組列表用的是「再點一次 Modules
標籤」，而 Navigation 的 multiple back stacks 會恢復該標籤自己保存的目標，於是又回到了
詳情頁。文件名說是列表，內容是詳情頁，這正是 BUG-010 那三張同 sha256 文件的同類毛病，
所以按同樣的規矩處理。它本該證明的東西由 `17` 補上（在 7034 上、且是重啟後沒有保存堆棧
時採的）。編號 `13` 因此在上表中缺號 —— 留着空號比把後面全部重排更不容易出錯。

## 採集這些截圖時撞到的兩個 ROM 行為

寫在這裡是因為它們會讓 UI 驗證得出假結論：

1. **`KEYCODE_WAKEUP` 會把 `com.xtc.i3launcher` 拉到前台**，即使屏幕本來就亮著。
   用它「保持喚醒」會靜默地把管理器踢到後台，截出來的是啟動器桌面，看着像管理器崩了。
   保持屏幕不滅要用觸摸事件，不要用這個鍵值。
2. **`mScreenOffTimeoutSetting=3000`**，而且 ROM 會把改大的值改回去。
   `settings put system screen_off_timeout` 被這個 ROM 屏蔽（回 `INVALID COMMAND.`），
   但 `content insert --uri content://settings/system --bind name:s:screen_off_timeout
   --bind value:s:600000` 能寫進去 —— 只是幾分鐘後會被覆蓋回 3000。所以每一步操作
   都要先自愈（鎖屏就解鎖、不在前台就重新拉起），再執行動作，全程壓在 3 秒內。

解鎖手勢 `input swipe 208 440 208 60 300` 本身也是這個 ROM 的「回主屏」手勢，所以順序
只能是**先解鎖、再啟動管理器**，反過來會把剛啟動的管理器滑掉。

寄生管理器的啟動命令（宿主是 `com.android.shell`）：

```sh
am start -a android.intent.action.MAIN \
  -c org.lsposed.manager.LAUNCH_MANAGER \
  -n com.android.shell/.BugreportWarningActivity
```
