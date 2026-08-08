# LSPosed 設置頁面崩潰修復

**問題**: 在特定設備上打開 LSPosed 管理器的設置頁面時立即崩潰

**崩潰日誌**:
```
java.lang.NullPointerException: Attempt to invoke virtual method 
'boolean android.content.pm.ShortcutManager.isRequestPinShortcutSupported()' 
on a null object reference
	at org.lsposed.manager.util.ShortcutUtil.isRequestPinShortcutSupported(ShortcutUtil.java:141)
	at org.lsposed.manager.ui.fragment.SettingsFragment$PreferenceFragment.onCreatePreferences(SettingsFragment.java:191)
```

---

## 根本原因

`ShortcutUtil` 的所有方法都假設 `context.getSystemService(ShortcutManager.class)` 返回非 null 值,但在某些定製 ROM(如測試設備 Z11_SN / XTC ROM / Android 11)上,該系統服務**不可用**,`getSystemService` 返回 `null`。

當用戶打開設置頁面時:
1. `SettingsFragment.java:191` 調用 `isRequestPinShortcutSupported(requireContext())`
2. `ShortcutUtil.java:140` 取得 `sm = getSystemService(ShortcutManager.class)` → **null**
3. `sm.isRequestPinShortcutSupported()` → **NullPointerException**
4. 管理器崩潰並退出

---

## 修復方案

在 `ShortcutUtil.java` 所有訪問 `ShortcutManager` 的方法中增加 **null 檢查**,當服務不可用時:
- `isRequestPinShortcutSupported` → 返回 `false`(UI 顯示"不支持")
- `requestPinLaunchShortcut` → 返回 `false`(靜默失敗)
- `updateShortcut` → 返回 `false`
- `isLaunchShortcutPinned` → 返回 `false`

這些方法的語義都是"快捷方式功能是否可用/成功",返回 `false` 符合預期,UI 會禁用相關功能但不會崩潰。

---

## 修改詳情

### `ShortcutUtil.java`

#### 1. `isRequestPinShortcutSupported` (line 139-142)

**原代碼**:
```java
public static boolean isRequestPinShortcutSupported(Context context) throws RuntimeException {
    var sm = context.getSystemService(ShortcutManager.class);
    return sm.isRequestPinShortcutSupported();
}
```

**修復後**:
```java
public static boolean isRequestPinShortcutSupported(Context context) throws RuntimeException {
    var sm = context.getSystemService(ShortcutManager.class);
    if (sm == null) return false;
    return sm.isRequestPinShortcutSupported();
}
```

#### 2. `requestPinLaunchShortcut` (line 144-151)

**原代碼**:
```java
public static boolean requestPinLaunchShortcut(Runnable afterPinned) {
    if (!App.isParasitic) throw new RuntimeException();
    var context = App.getInstance();
    var sm = context.getSystemService(ShortcutManager.class);
    if (!sm.isRequestPinShortcutSupported()) return false;
    return sm.requestPinShortcut(getShortcutBuilder(context).build(),
            registerReceiver(context, afterPinned));
}
```

**修復後**:
```java
public static boolean requestPinLaunchShortcut(Runnable afterPinned) {
    if (!App.isParasitic) throw new RuntimeException();
    var context = App.getInstance();
    var sm = context.getSystemService(ShortcutManager.class);
    if (sm == null || !sm.isRequestPinShortcutSupported()) return false;
    return sm.requestPinShortcut(getShortcutBuilder(context).build(),
            registerReceiver(context, afterPinned));
}
```

#### 3. `updateShortcut` (line 153-160)

**原代碼**:
```java
public static boolean updateShortcut() {
    if (!isLaunchShortcutPinned()) return false;
    var context = App.getInstance();
    var sm = context.getSystemService(ShortcutManager.class);
    List<ShortcutInfo> shortcutInfoList = new ArrayList<>();
    shortcutInfoList.add(getShortcutBuilder(context).build());
    return sm.updateShortcuts(shortcutInfoList);
}
```

**修復後**:
```java
public static boolean updateShortcut() {
    if (!isLaunchShortcutPinned()) return false;
    var context = App.getInstance();
    var sm = context.getSystemService(ShortcutManager.class);
    if (sm == null) return false;
    List<ShortcutInfo> shortcutInfoList = new ArrayList<>();
    shortcutInfoList.add(getShortcutBuilder(context).build());
    return sm.updateShortcuts(shortcutInfoList);
}
```

#### 4. `isLaunchShortcutPinned` (line 162-171)

**原代碼**:
```java
public static boolean isLaunchShortcutPinned() {
    var context = App.getInstance();
    var sm = context.getSystemService(ShortcutManager.class);
    for (var info : sm.getPinnedShortcuts()) {
        if (SHORTCUT_ID.equals(info.getId())) {
            return true;
        }
    }
    return false;
}
```

**修復後**:
```java
public static boolean isLaunchShortcutPinned() {
    var context = App.getInstance();
    var sm = context.getSystemService(ShortcutManager.class);
    if (sm == null) return false;
    for (var info : sm.getPinnedShortcuts()) {
        if (SHORTCUT_ID.equals(info.getId())) {
            return true;
        }
    }
    return false;
}
```

---

## 影響範圍

**修復前**:
- 在不支持 `ShortcutManager` 的設備上(部分定製 ROM),打開設置頁面必崩潰
- 影響所有需要訪問設置的用戶操作(開關通知、備份、主題切換等)

**修復後**:
- 設置頁面正常打開
- "添加快捷方式"功能顯示為禁用狀態,提示"不支持"
- 其他設置功能(通知、備份、主題、語言等)完全正常
- 在支持 `ShortcutManager` 的設備上行為不變

---

## 測試建議

### 已知受影響設備
- **XTC ROM** (本次崩潰設備:Z11_SN, Android 11)
- 其他移除或修改系統服務的定製 ROM

### 測試步驟
1. 刷入修復後的包
2. 打開 LSPosed 管理器
3. 進入"設置"頁面
4. 確認:
   - ✅ 頁面正常打開,無崩潰
   - ✅ "添加快捷方式"顯示為禁用(灰色),提示"不支持固定快捷方式"
   - ✅ 其他設置項(通知、備份、主題)可正常操作

### 回歸測試(原生 ROM)
在支持 `ShortcutManager` 的設備上(如 Pixel、原生 AOSP):
- ✅ "添加快捷方式"正常啟用
- ✅ 點擊後可成功固定快捷方式

---

## 相關文件

- `app/src/main/java/org/lsposed/manager/util/ShortcutUtil.java` (修復檔)
- `app/src/main/java/org/lsposed/manager/ui/fragment/SettingsFragment.java` (調用處)

---

## 技術背景

`ShortcutManager` 是 Android 7.1 (API 25) 引入的系統服務,用於:
- 固定快捷方式到啟動器(Pin Shortcuts)
- 動態創建/更新應用快捷方式
- 查詢已固定的快捷方式

**為何某些設備返回 null?**
- 定製 ROM 為了精簡系統或避免與自家啟動器衝突,會移除或替換該服務
- `getSystemService` 對不存在的服務返回 `null` 而非拋異常
- 標準做法是在調用服務方法前先檢查 null

**原代碼為何未檢查?**
- LSPosed 主要針對原生 Android 與 AOSP 系統開發
- 大部分設備都有該服務,開發者未遇到此場景
- 這是典型的"在常見設備上正常,定製 ROM 上崩潰"的兼容性問題

---

**修復日期**: 2026-08-08（測試設備 Z11_SN / XTC ROM / Android 11）
