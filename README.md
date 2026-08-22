# LSPosed Framework (fork)

A fork of [LSPosed](https://github.com/LSPosed/LSPosed) adding a command line
interface for module activation, a crash fix for custom ROMs, and smartwatch
layout support. Everything below the "Upstream README" heading is unchanged from
upstream.

## What this fork adds

- **`lspd-cli`** — enable a module and tick its scopes from a root shell in one
  command, without opening the manager app. Speaks to the daemon over an
  abstract socket using the same `ConfigManager` calls the manager UI uses.
- **Settings crash fix** — `ShortcutManager` is absent on some custom ROMs, where
  the unguarded `getSystemService` call made the settings page crash on open.
- **Watch support** — a compact theme overlay applied before inflation, plus code paths
  that branch on `PackageManager.FEATURE_WATCH`. The `-watch` resource qualifier keys off
  `uiMode`, not that feature, so it is never selected on the reference device; see
  [docs/WATCH_ADAPTATION.md](docs/WATCH_ADAPTATION.md).

Both the Riru and Zygisk flavors carry all three. See [docs/](docs/) for details.

## Build

```sh
scripts/build.sh                 # both flavors, debug
scripts/build.sh release         # both flavors, release
```

PowerShell: `.\scripts\build.ps1`

The script fetches submodules, clones and patches the two libxposed
dependencies at pinned commits, publishes them to mavenLocal, builds both
flavors in a single Gradle invocation, and verifies the two zips are in parity.
Artifacts land in `magisk-loader/release/`.

Requires JDK 17, Android SDK Platform 34, Build-Tools 34.0.0, NDK
26.1.10909125, and CMake 3.22.1. Full setup notes — including why the
libxposed `100` tags are *not* the right commits — are in
[docs/BUILDING.md](docs/BUILDING.md).

Both flavors are built together on purpose: `app` and `daemon` are not
flavor-specific, so building them separately lets the two zips drift apart.
`scripts/verify-parity.sh` asserts they haven't.

## Documentation

- [docs/BUILDING.md](docs/BUILDING.md) — build script, toolchain, dependency pinning
- [docs/CLI.md](docs/CLI.md) — `lspd-cli` usage, wire protocol, permission model
- [docs/CRASH_FIX.md](docs/CRASH_FIX.md) — the `ShortcutManager` NPE fix
- [docs/WATCH_ADAPTATION.md](docs/WATCH_ADAPTATION.md) — watch layout approach
- [docs/TESTING.md](docs/TESTING.md) — on-device test record and coverage gaps

## License

GPL-3, inherited from upstream LSPosed.

---

# Upstream README

[![Crowdin](https://img.shields.io/badge/Localization-Crowdin-blueviolet?logo=Crowdin)](https://lsposed.crowdin.com/lsposed) [![Channel](https://img.shields.io/badge/Follow-Telegram-blue.svg?logo=telegram)](https://t.me/LSPosed) [![Chat](https://img.shields.io/badge/Join-QQ%E9%A2%91%E9%81%93-red?logo=tencent-qq&logoColor=red)](https://qun.qq.com/qqweb/qunpro/share?_wv=3&_wwv=128&inviteCode=Xz9dJ&from=246610&biz=ka) [![Download](https://img.shields.io/github/v/release/LSPosed/LSPosed?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/LSPosed/LSPosed/releases/latest) [![Total](https://shields.io/github/downloads/LSPosed/LSPosed/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/LSPosed/LSPosed/releases)

## Introduction 

A Riru / Zygisk module trying to provide an ART hooking framework which delivers consistent APIs with the OG Xposed, leveraging LSPlant hooking framework.

> Xposed is a framework for modules that can change the behavior of the system and apps without touching any APKs. That's great because it means that modules can work for different versions and even ROMs without any changes (as long as the original code was not changed too much). It's also easy to undo. As all changes are done in the memory, you just need to deactivate the module and reboot to get your original system back. There are many other advantages, but here is just one more: multiple modules can do changes to the same part of the system or app. With modified APKs, you have to choose one. No way to combine them, unless the author builds multiple APKs with different combinations.

## Supported Versions

Android 8.1 ~ 14

## Install

1. Install Magisk v24+
2. (For Riru flavor) Install [Riru](https://github.com/RikkaApps/Riru/releases/latest) v26.1.7+
3. [Download](#download) and install LSPosed in Magisk app
4. Reboot
5. Open LSPosed manager from notification
6. Have fun :)

## Download

- For stable releases, please go to [Github Releases page](https://github.com/LSPosed/LSPosed/releases)
- For canary build, please check [Github Actions](https://github.com/LSPosed/LSPosed/actions/workflows/core.yml?query=branch%3Amaster)

Note: debug builds are only available in Github Actions.

## Get Help
**Only bug reports from **THE LATEST DEBUG BUILD** will be accepted.**
- GitHub issues: [Issues](https://github.com/LSPosed/LSPosed/issues/)
- (For Chinese speakers) 本项目只接受英语**标题**的issue。如果您不懂英语，请使用[翻译工具](https://www.deepl.com/zh/translator)

## For Developers

Developers are welcome to write Xposed modules with hooks based on LSPosed Framework. A module based on LSPosed framework is fully compatible with the original Xposed Framework, and vice versa, a Xposed Framework-based module will work well with LSPosed framework too.

- [Xposed Framework API](https://api.xposed.info/)

We use our own module repository. We welcome developers to submit modules to our repository, and then modules can be downloaded in LSPosed.

- [LSPosed Module Repository](https://github.com/Xposed-Modules-Repo)

## Community Discussion

- Telegram: [@LSPosed](https://t.me/s/LSPosed)

Notice: These community groups don't accept any bug report, please use [Get help](#get-help) to report.

## Translation Contributing

You can contribute translation [here](https://lsposed.crowdin.com/lsposed).

## Credits 

- [Magisk](https://github.com/topjohnwu/Magisk/): makes all these possible
- [Riru](https://github.com/RikkaApps/Riru): provides a way to inject code into zygote process
- [XposedBridge](https://github.com/rovo89/XposedBridge): the OG Xposed framework APIs
- [Dobby](https://github.com/jmpews/Dobby): used for inline hooking
- [LSPlant](https://github.com/LSPosed/LSPlant): the core ART hooking framework
- [EdXposed](https://github.com/ElderDrivers/EdXposed): fork source
- ~[SandHook](https://github.com/ganyao114/SandHook/): ART hooking framework for SandHook variant~
- ~[YAHFA](https://github.com/rk700/YAHFA): previous ART hooking framework~
- ~[dexmaker](https://github.com/linkedin/dexmaker) and [dalvikdx](https://github.com/JakeWharton/dalvik-dx): to dynamically generate YAHFA hooker classes~
- ~[DexBuilder](https://github.com/LSPosed/DexBuilder): to dynamically generate YAHFA hooker classes~

## License

LSPosed is licensed under the **GNU General Public License v3 (GPL-3)** (http://www.gnu.org/copyleft/gpl.html).
