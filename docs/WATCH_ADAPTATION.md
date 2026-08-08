# 手錶適配

讓 LSPosed 管理器在智能手錶上可用。參考設備 Z11_SN 上報
`sw159dp-w159dp-h179dp @416dpi` —— 可用寬度不到手機的一半，管理器原本按手機尺寸
寫死的高度與圖標在這種屏幕上會把 Fragment 內容擠到幾乎看不見。

## 選擇條件：uiMode = watch，不是屏幕寬度

適配資源放在 `layout-watch/` 與 `values-watch/`，由 Android 資源系統在設備聲明
`android.hardware.type.watch` 時自動選用（編譯進 APK 後表現為
`res/layout-watch-v20/`、`res/values-watch-v20/`，`v20` 是 watch uiMode 引入的 API 級別）。

用 `-watch` 而不是 `-w240dp` 這類寬度限定符，是因為寬度限定符會誤傷手機：手機橫屏時
可用寬度同樣落在小屏區間，會被錯誤地切到緊湊佈局。`-watch` 只匹配真正的手錶設備，
手機在任何方向都走原版佈局，回歸風險為零。

## 佈局：保留底部導航，壓縮而非替換

`layout-watch/activity_main.xml` 與手機版 `layout/activity_main.xml`
**保持完全相同的視圖層級與 id**，只調整尺寸與標籤顯示：

- 仍是 `BottomNavigationView`（id 仍為 `nav`），不改成頂部工具欄菜單
- `app:labelVisibilityMode="unlabeled"` —— 159dp 寬放不下 5 個帶文字的 tab，隱藏標籤後
  只剩圖標，恰好排得開
- 導航欄高度、圖標尺寸、內邊距改為引用 dimens，由 `values-watch/` 給出手錶值

這樣做的關鍵好處是 **`MainActivity.java` 完全不用改**。視圖 id 和類型都沒變，
`binding.nav` 在兩種佈局下都存在且都是 `NavigationBarView`，原有的
`NavigationUI.setupWithNavController(nav, navController)` 與所有
`nav.setSelectedItemId(...)` 調用照常工作，不存在 null 檢查或雙分支導航邏輯。
Java 代碼零改動，也就沒有引入新的崩潰路徑。

## 尺寸

`values-watch/dimens.xml` 覆蓋三個原有值，並給出導航欄的手錶尺寸：

| dimen | 手機（`values/`） | 手錶（`values-watch/`） |
|---|---|---|
| `app_icon_size` | 48dp | 24dp |
| `tab_layout_height` | 48dp | 32dp |
| `home_primary_elevation` | 6dp | 1dp |
| `watch_nav_height` | 56dp | 36dp |
| `watch_nav_icon_size` | 24dp | 20dp |
| `watch_nav_item_padding` | 8dp | 4dp |

`watch_nav_*` 三個值只被 `layout-watch/` 引用，但**必須同時在 `values/` 中定義**，
否則其他配置下資源無法解析、編譯直接失敗 —— 這也是 `values/dimens.xml`
出現這三個看似無用條目的原因。手機值填的是 Material 默認尺寸，保持語義正確。

## 驗證狀態

- APK 中確認包含 `res/layout-watch-v20/activity_main.xml` 與 `res/values-watch-v20/dimens.xml`
- 手機回歸：資源未被選中，底部導航與尺寸與原版一致
- **未在真實手錶或 Wear OS 模擬器上實測** —— 佈局在小屏上的實際觀感待驗證

模擬器驗證步驟：Android Studio → Device Manager → Create Virtual Device →
Wear OS 類別 → 安裝 manager.apk → 確認 5 個導航圖標排布不重疊、Fragment 內容有足夠空間。

## 已知限制

- 極小屏幕（< 140dp）未覆蓋
- 用戶設置超大字號時導航圖標可能擠壓
- 圓形錶盤四角未做額外處理（`values-round/` 未使用；如需可再加一層限定符）
