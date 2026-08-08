# 構建

## 一鍵構建

```sh
scripts/build.sh                 # 兩個 flavor，debug
scripts/build.sh release         # 兩個 flavor，release（需簽名配置）
scripts/build.sh debug riru      # 只構建單個 flavor
FORCE_DEPS=1 scripts/build.sh    # 強制重新拉取並發布 libxposed 依賴
```

Windows PowerShell：

```powershell
.\scripts\build.ps1
.\scripts\build.ps1 -BuildType release
.\scripts\build.ps1 -ForceDeps
```

腳本會依次完成：拉取 git submodule → clone 並修補 libxposed 依賴 → 發布到
mavenLocal → 構建 zip → 校驗兩個 flavor 的一致性。產物在
`magisk-loader/release/`。

依賴發布結果會緩存在 `.deps/`（已 gitignore），二次構建自動跳過。

## 環境要求

| 組件 | 版本 | 說明 |
|---|---|---|
| JDK | 17 | 上游 CI 用的版本；21 會導致 libxposed 依賴 JVM target 不一致 |
| Android SDK Platform | 34 | |
| Build-Tools | 34.0.0 | |
| NDK | 26.1.10909125 | 版本號寫死在 `build.gradle.kts` 的 `androidCompileNdkVersion` |
| CMake | 3.22.1 | |

SDK 位置通過 `local.properties` 指定（該文件不入庫）：

```properties
sdk.dir=C:/Android/Sdk
```

Windows 上請用正斜槓，避免反斜槓被當作轉義。若用戶目錄含非 ASCII 字符，把
`GRADLE_USER_HOME` 指向純 ASCII 路徑，否則部分 Gradle 插件會解析失敗。

`external/` 下的 lsplant / dobby / fmt / cxx 是 git submodule，native 構建直接讀取
它們的源碼。忘記拉取會在 CMake 階段報一個很難定位的錯誤，所以構建腳本會先自行檢查：

```sh
git submodule update --init --recursive
```

## libxposed 依賴陷阱

LSPosed 編譯前必須先讓這兩個構件進入本地 Maven 倉庫：

- `io.github.libxposed:api:100`
- `io.github.libxposed:interface:100`

**兩個庫的 `100` tag 都不是 LSPosed HEAD 需要的那個 `100`。** 它們長期把版本號停在
`100` 持續演進，而 tag 只打在很早的提交上。也就是說版本號無法用來定位正確的代碼，
必須按 **API 形狀** 挑提交 —— 挑錯會直接編譯失敗：

| 依賴 | 需要的提交 | 挑錯時的報錯 |
|---|---|---|
| `interface:100` | tag `100` 工程 + `ee4c516` 的 `IXposedService.aidl` | `LSPModuleService 不是抽象的，未覆蓋 openRemoteFile(String,int)` |
| `api:100` | `5458273`（Fix docs path #18） | `LSPosedContext 不是抽象的，未覆蓋 <T>invokeSpecial(Constructor<T>,T,Object...)` |

選點依據：

- **interface 選 `ee4c516`**，而不是更晚的 `e58452c` —— 後者新增了
  `getRunningTargets()`，而 LSPosed HEAD 的 `LSPModuleService` 只實現 14 個方法、不含它。
  同時以 tag `100` 的工程為底（Gradle 7.6，構建簡單），只換入這一個 aidl 文件。
- **api 選 `5458273`**，而不是更晚的 `55efdf9` / `88cc078` —— 後者新增了
  `invokeOrigin` / `invokeSpecial` 的 `Constructor` 重載，而 `LSPosedContext` 並未實現。

`scripts/build.sh` 已把這些提交寫死並自動處理，不需要手動操作。改動 pin 之後
腳本會自動重新發布（`.deps/.published` 記錄了上次發布的提交）。

網絡失敗會自動重試 3 次（間隔 5s / 10s / 20s）。若完全無法訪問 GitHub，
可指向本地鏡像離線構建（鏡像需包含上面的 pin 提交）：

```sh
LIBXPOSED_API_REPO=/path/to/api \
LIBXPOSED_SERVICE_REPO=/path/to/service \
  scripts/build.sh
```

PowerShell 下用 `$env:LIBXPOSED_API_REPO = 'C:\mirrors\api'`。

腳本另外會做兩處與本機工具鏈對齊的降級，兩個庫都只是接口/註解庫，降級是安全的：

- api 及其 `checks` 子模塊：`JavaVersion.VERSION_21` → `VERSION_17`
  （兩者必須一致，否則報 "Inconsistent JVM-target compatibility"）
- service：`compileSdk 33` / Build-Tools `33.0.1` → `34` / `34.0.0`

手動復現的等價步驟：

```sh
git clone https://github.com/libxposed/api && cd api
git checkout 5458273
# 把 api/build.gradle.kts 與 checks/build.gradle.kts 的 VERSION_21 改為 VERSION_17
./gradlew :api:publishToMavenLocal

git clone https://github.com/libxposed/service && cd service
git checkout 100
git checkout ee4c516 -- interface/src/main/aidl/io/github/libxposed/service/IXposedService.aidl
# 把 compileSdk 改為 34、buildToolsVersion 改為 34.0.0
./gradlew :interface:publishToMavenLocal
```

## 為什麼兩個 flavor 要一起構建

`app`（管理器）與 `daemon` 模塊**不區分 flavor**，Riru 與 Zygisk 兩個 zip 裡的
`manager.apk`、`daemon.apk`、`lspd-cli` 應當完全一致，只有 native loader
(`liblspd.so`) 和幾個 flavor 標記不同。

單獨構建一個 flavor、改代碼、再單獨構建另一個，會讓兩個 zip 來自不同的源碼狀態 ——
這正是實際發生過的問題：先構建的 zygisk zip 裡沒有後來加入的手錶佈局，而 riru zip 裡有。
所以 `scripts/build.sh` 默認在**一次 Gradle 調用**裡構建兩個 flavor。

構建後可隨時單獨校驗：

```sh
scripts/verify-parity.sh debug
```

它會解包兩個 zip 並斷言：共享文件逐字節相同、CLI 與手錶佈局在兩邊都存在、
flavor 標記正確、native loader 確實按不同 API 編譯。

## 直接用 Gradle

```sh
./gradlew :magisk-loader:zipRiruDebug :magisk-loader:zipZygiskDebug
./gradlew zipAll            # 4 個 zip：兩 flavor × debug/release
```

調試期常用（需連接設備）：

```sh
./gradlew :magisk-loader:flashMagiskAndRebootZygiskDebug   # 刷入並重啟
./gradlew :magisk-loader:reRunApp                          # 只推管理器 APK 並重啟守護進程
```

release 構建需要在 `gradle.properties` 提供簽名配置（`androidStoreFile`、
`androidStorePassword`、`androidKeyAlias`、`androidKeyPassword`），
否則 debug 簽名之外的產物無法安裝。
