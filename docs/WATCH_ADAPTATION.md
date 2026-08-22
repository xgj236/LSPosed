# 手錶適配

讓 LSPosed 管理器在智能手錶上可用。參考設備 Z11_SN 上報
`sw159dp-w159dp-h179dp @416dpi` —— 可用寬度不到手機的一半。管理器原本按手機尺寸寫死
的高度與圖標在這種屏幕上會把 Fragment 內容擠到幾乎看不見。

實際排版寬度不是 159dp：`BaseActivity.scaleForWatch()` 把 density 縮到
`WATCH_TARGET_WIDTH_DP = 240f`，416 / 240 = 1.7333，所以每一頁真正面對的視口是
**240 × 270dp**。下面所有 dp 數字都是這個視口裏的 dp，不是 159dp 那個。這一點在讀舊
資源值時尤其要緊 —— 早期那些手錶值是照 159dp 寫的，放進 240dp 視口就明顯偏小。

## 選擇條件：`-watch` 在這台設備上永遠選不中

**這一節原先寫錯了，而且錯的正是整套適配的前提。** 原文說 `layout-watch/` 與
`values-watch/` 會「在設備聲明 `android.hardware.type.watch` 時自動選用」。不對：
`-watch` 限定符匹配的是 **configuration 的 `uiMode = UI_MODE_TYPE_WATCH`**，跟有沒有
聲明那個 hardware feature 是兩件事。參考設備兩者不一致：

```sh
$ adb shell pm list features | grep watch
feature:android.hardware.type.watch                 # 聲明了

$ adb shell am get-config
config: zh-rCN-ldltr-sw159dp-w159dp-h179dp-small-notlong-notround-nowidecg-lowdr        -port-416dpi-finger-keysexposed-nokeys-navhidden-nonav-468x416-v30
                                                    # 整串沒有 watch 這個 token

$ adb shell dumpsys uimode | grep mCurUiMode
  mCurUiMode=0x0                                    # UI_MODE_TYPE_NORMAL，不是 0x6
```

所以在這台設備上，**每一個 `-watch` 限定的資源都是死代碼**：運行時選中的是手機的
`layout/` 與 `values/`。這解釋了一件之前一直沒解釋的事 —— `verify-parity.sh` 斷言
「資源表裏有帶 `(watch)` 限定符的條目」是真的通過了，APK 裏確實打進去了那些資源，
但打進去和被選中是兩回事，斷言只證明了前者。

原文還說「`-watch` 只匹配真正的手錶設備，回歸風險為零」。回歸風險為零這半句仍然成立，
只是原因不同：它匹配不到任何東西，包括這隻手錶。

XTC 這類廠商 ROM 出現這種組合並不奇怪 —— 它們基於手機 AOSP 而不是 Wear OS，`uiMode`
從來沒被設成 watch，hardware feature 則是為了讓應用商店和權限策略認出設備類型才加的。

## 真正生效的兩條路徑

既然限定符走不通，而 `createConfigurationContext()` 又被寄生模式禁掉（見下），
能用的只剩兩條，都以 `BaseActivity.isWatch()` 為判據 —— 它讀的是
`PackageManager.FEATURE_WATCH`（`BaseActivity.java:74`），也就是這台設備**確實**上報的
那一個。

**一、主題覆蓋 `ThemeOverlay.Watch`**（`values/themes_overlay.xml`），在
`onApplyUserThemeResource()` 裏套到 Activity 主題上，早於任何 content 的 inflate，
所以 Fragment 佈局裏的 `?attr/...` 都會解析成手錶值：

| 屬性 | 手機 | 手錶 |
|---|---|---|
| `collapsingToolbarLayoutLargeSize` | 152dp | 56dp（`watch_collapsing_toolbar_size`） |
| `collapsingToolbarLayoutLargeStyle` | `Widget.Material3.CollapsingToolbar.Large` | `Widget.Watch.CollapsingToolbar` |
| `dialogPreferredPadding` | 24dp | 12dp（`watch_dialog_padding`） |

只覆蓋一個 style 屬性就夠是因為五個 Fragment 的 app bar 統一寫
`style="?attr/collapsingToolbarLayoutLargeStyle"`，沒有第二處要同步。

**二、代碼裏按 `isWatch()` 分支**，用在主題屬性表達不了的地方：

- `MainActivity.compactNavForWatch()` —— 壓縮底部導航（高度、圖標、標籤可見性）。
  必須用代碼是因為 `LABEL_VISIBILITY_UNLABELED` 是 `NavigationBarView` 的運行時設置，
  而原先靠的是 `layout-watch/activity_main.xml` 的 `app:labelVisibilityMode`，選不中。
  五個 item 在 `LABEL_VISIBILITY_AUTO` 下只給選中項畫標籤，那個標籤就畫在 468px 屏幕
  的底邊之外。
- `AppListFragment` —— 模組詳情頁的 fab 改 `SIZE_MINI`。

## 佈局：手錶上被 inflate 的是手機佈局

`layout-watch/activity_main.xml` 仍在倉庫裏，與手機版**保持完全相同的視圖層級與 id**，
但在這台設備上不會被選中。真正被 inflate 的是 `layout/activity_main.xml`，導航的壓縮
由 `MainActivity.compactNavForWatch()` 在運行時做，讀的是同一組
`watch_nav_*` dimens —— 兩條路徑共用一組數字，就不會各自漂移。

原文「`MainActivity.java` 完全不用改，Java 代碼零改動」現在不成立了，而且不可能成立：
限定符選不中，就必須有代碼把它做的事做掉。視圖 id 和類型仍然沒變，`binding.nav` 在兩種
佈局下都存在且都是 `NavigationBarView`，`NavigationUI.setupWithNavController(...)` 與
`nav.setSelectedItemId(...)` 照常工作，所以沒有雙分支的導航邏輯，只多了一個在
非手錶設備上立即 return 的方法。

## 折疊工具欄：它在這塊屏幕上永遠展不開也收不起

app bar 高度從 152dp 壓到 56dp 之後出了一個不直觀的問題：**折疊工具欄卡在「已展開」
狀態，而且出不來**。原因是它的總高（`collapsingToolbarLayoutLargeSize` = 56dp）等於
裏面 pin 住的 `MaterialToolbar` 的高（`actionBarSize` 也是 56dp），於是可滾動範圍是 0，
`expandedFraction` 永遠是 1。

後果是**只有 expanded 那一組幾何值會被用到**，而 Material 的 `.Large` 樣式是照 152dp 的
盒子算 expanded 的：28sp 兩行標題 + `expandedTitleMargin*`。在 56dp 的盒子裏，這組值把
標題畫到屏幕頂邊之外（7033 實測，`docs/screenshots/14`）；而在 7032 的 96dp 盒子裏，
它畫在返回箭頭上面（`docs/screenshots/10` 左上角）。兩種都不是高度問題，所以改高度
都治不了。

`Widget.Watch.CollapsingToolbar` 的做法是**把 expanded 的幾何值改寫成 collapsed 的樣子**
（`values/themes_overlay.xml`）：文字降到工具欄自己的字號（`textAppearanceTitleMedium` /
`textAppearanceBodySmall`），橫向留出實測的 gutter —— 返回箭頭 `[0,0][97,90]` 是 56dp，
搜索加溢出菜單 `x 264..416` 是 88dp，中間 96dp 給標題。這正是 collapsed 狀態會給的盒子
（collapsed bounds 取自 Toolbar 裏的 dummyView），只是靠樣式手寫了一遍。

順帶記一個取捨：五個頂級頁面沒有返回箭頭，卻同樣被縮進 56dp。單一個靜態樣式分不出有沒有
返回鍵，而讓標題在頂級頁和詳情頁之間橫跳 40dp 比縮進更難看，所以保持一致。頂級頁的標題
都是兩個字，96dp 綽綽有餘。

## 尺寸

**生效的一組**在 `values/dimens.xml`（無限定符，所以兩條選擇路徑都讀得到）：

| dimen | 值 | 誰在用 |
|---|---|---|
| `watch_nav_height` | 48dp | `compactNavForWatch()`；= Material 最小觸控高 |
| `watch_nav_icon_size` | 20dp | 同上 |
| `watch_nav_item_padding` | 6dp | 同上 |
| `watch_collapsing_toolbar_size` | 56dp | `ThemeOverlay.Watch` |
| `watch_dialog_padding` | 12dp | `ThemeOverlay.Watch` |
| `watch_appbar_title_margin_start` | 56dp | `Widget.Watch.CollapsingToolbar` |
| `watch_appbar_title_margin_end` | 88dp | 同上 |
| `watch_appbar_title_margin_bottom` | 4dp | 同上 |

**選不中的一組**留在 `values-watch/dimens.xml`：`app_icon_size` 24dp、
`tab_layout_height` 32dp、`home_primary_elevation` 1dp。它們只對真正上報
`uiMode=watch` 的設備有效，手上沒有這種設備，因此**未經驗證**；而且是照 159dp 視口寫的，
放進 240dp 視口很可能偏小。沒有刪也沒有猜著改，原樣留著並在文件頭寫清楚。

導航的三個值原先也在 `values-watch/` 裏（高 36dp），已經移走：36dp 在 240dp 視口下低於
Material 的 48×48dp 最小觸控區，而且那份覆蓋在這台設備上根本不生效，留著只會讓人以為
導航是 36dp。

## 全局 density 覆蓋：為什麼在這裡，以及為什麼不能換成 context

`BaseActivity.scaleForWatch()` 把 `Resources` 的 density 縮到 `WATCH_TARGET_WIDTH_DP`，
是讓所有頁面在 416×468px / 416dpi 上勉強可用的東西。這是有意的取捨，不是漏做適配：
現有頁面全都依賴它，在補齊各頁的手錶佈局之前拿掉它，每一頁會同時變差。

兩個容易被誤判的細節：

- **字號縮放沒有被破壞。** `scaledDensity` 是按 `scaledDensity / density` 的比例推導的，
  不是直接覆蓋，所以用戶的系統字號設置仍然生效。
- **它就地改 `Resources`，而不是派生 configuration context。** 這一條是硬約束：寄生模式下
  管理器與 `system_server` 共用進程，給那個進程換一個 configuration context 會殺掉 LSPosed
  注入在裡面的 bridge。同理，`configuration` 的 dp 範圍必須跟着 density 一起改 —— 窗口按
  configuration 定尺寸、內容按 metrics 測量，兩者不一致會讓對話框按未縮放的屏幕排版而被裁切。

BUG-007 原本建議「用顯式的 phone / compact-watch profile 取代全局 density 變換」，路徑
只能是資源覆蓋加主題/代碼分支，不能走 `createConfigurationContext()`。而在這台設備上，
「資源覆蓋」還得再排除 `-watch` 限定符那一條 —— 剩下的就是上面那兩條路徑。

## 驗證狀態

已在真機上實測（Z11_SN，Riru flavor，v1.9.2.10 / 7034），逐頁量了 bounds 並留了截圖，
見 [TESTING.md](TESTING.md#手錶適配驗證) 與 [screenshots/README.md](screenshots/README.md)。
關鍵數字（7032 → 7034）：

| | 7032 | 7034 |
|---|---|---|
| app bar | 166px | 97px（56dp） |
| 內容區 | 163px | 288px |
| 底部導航 | 139px | 83px（47.9dp，仍 ≥ 48dp 觸控最小值） |
| 詳情頁開關 / fab | 橫向重疊 55px，開關狀態不可見、點不到 | 縱向間隔 49px，開關可見可點 |
| app bar 標題 | 被返回箭頭壓住 | 完整顯示，與返回鍵、搜索、溢出菜單都不重疊 |

手機回歸：`isWatch()` 為 false，`ThemeOverlay.Watch` 不套用，`compactNavForWatch()` 立即
return，底部導航與尺寸與原版一致。

**仍未實測**：真正上報 `uiMode=watch` 的 Wear OS 設備（`values-watch/` 那一組值），
以及圓形錶盤。

## 已知限制

- **設置頁的 preference 行仍然擁擠**：圖標與開關各佔一側 gutter 後，標題只剩約 90dp，
  「安全 DNS（DoH）」會折成兩行、摘要折成三行並被裁（`docs/screenshots/19`）。
  這是內容密度而不是 chrome，可以滾動，屬於下一輪的事
- 詳情頁標題盒子只有 96dp，長模組名與包名都會省略號截斷（`docs/screenshots/16`）
- 極小屏幕（< 140dp）未覆蓋
- 用戶設置超大字號時導航圖標可能擠壓
- 圓形錶盤四角未做額外處理（`values-round/` 未使用；如需可再加一層限定符）
- `values-watch/` 那一組值未在任何設備上生效過，見「尺寸」
