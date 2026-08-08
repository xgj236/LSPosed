# LSPosed 命令行激活模块 / 勾选作用域（lspd-cli）

本分支在官方 LSPosed 源码基础上，新增了**用命令行激活指定模块并勾选指定作用域**的能力，
无需打开管理器 App，即可在 root shell 里一条命令完成「启用模块 + 勾选目标应用」。

## 一、工作原理

```
  root shell                    LSPosed 常驻守护进程 (lspd)
 ┌──────────┐  abstract unix   ┌──────────────────────────┐
 │ lspd-cli │ ───socket:@lspd_ctl──▶ CommandListener ─▶ ConfigManager
 └──────────┘                  │   (与管理器完全相同的接口) │
                               └──────────────────────────┘
```

- 守护进程内新增 `CommandListener`（`daemon/.../service/CommandListener.java`），
  在抽象命名空间 socket `@lspd_ctl` 上监听单行文本命令，直接调用 `ConfigManager`
  的 `enableModule / setModuleScope / disableModule …`——**和管理器 App 走的是同一套逻辑**，
  因此改动即时写入数据库并刷新缓存，效果与在管理器里手动勾选完全一致。
- `lspd-cli`（`CliMain.java` + 同名包装脚本）是一个短生命周期的 `app_process` 客户端，
  把友好的命令行参数翻译成上面的线协议，连接 socket、发送、打印返回、按结果设置退出码。

新增/改动的文件：

| 文件 | 说明 |
|------|------|
| `daemon/src/main/java/org/lsposed/lspd/service/CommandListener.java` | 守护进程侧命令监听器（服务端） |
| `daemon/src/main/java/org/lsposed/lspd/cli/CliMain.java` | 命令行客户端入口（打包进 daemon.apk） |
| `daemon/src/main/java/org/lsposed/lspd/service/ServiceManager.java` | 系统服务就绪后启动监听器 |
| `daemon/proguard-rules.pro` | 保留 `CliMain.main`（release 混淆下反射入口不被裁剪） |
| `magisk-loader/magisk_module/lspd-cli` | 包装脚本，`app_process` 启动 `CliMain` |
| `magisk-loader/magisk_module/customize.sh` | 安装时提取 `lspd-cli` 并赋可执行权限 |
| `magisk-loader/magisk_module/sepolicy.rule` | 允许 root/su 域连接守护进程控制 socket |
| `magisk-loader/build.gradle.kts` | 把 `lspd-cli` 打进刷机 zip（并规范化为 LF 换行） |

## 二、用法

刷入模块并重启后，用 root 执行（模块目录名 Zygisk 版为 `zygisk_lsposed`，Riru 版为 `riru_lsposed`）：

```sh
CLI=/data/adb/modules/zygisk_lsposed/lspd-cli

# 核心功能：激活模块并勾选作用域（一步到位）
su -c "$CLI activate com.example.mod --scope com.android.settings,com.tencent.mm/0"

# 只启用/禁用模块
su -c "$CLI enable  com.example.mod"
su -c "$CLI disable com.example.mod"

# 追加 / 覆盖作用域
su -c "$CLI scope   com.example.mod --scope com.android.settings"            # 追加
su -c "$CLI scope   com.example.mod --scope com.android.settings --replace"  # 覆盖整个作用域集合
su -c "$CLI unscope com.example.mod --scope com.tencent.mm"                  # 取消勾选

# 让作用域应用立即生效（强制停止目标应用，下次启动即带 hook）
su -c "$CLI activate com.example.mod --scope com.tencent.mm --force-stop"

# 查询
su -c "$CLI list"                       # 已启用模块
su -c "$CLI getscope com.example.mod"   # 某模块当前作用域
su -c "$CLI ping"                       # 探活守护进程
```

参数说明：

- `<module>`：模块 App 的包名。
- `--scope, -s`：逗号分隔的目标应用列表，每项为 `包名` 或 `包名/用户ID`（用户 ID 默认 0，用于多用户/分身）。
- `--replace, -r`：用本次列表**覆盖**整个作用域集合（默认是**追加**）。
- `--force-stop, -f`：勾选后强制停止目标应用，使 hook 立即在其下次启动时生效。
- `activate` = `enable` + 勾选作用域，一条命令完成，即本需求的核心。

退出码：`0` 成功，`1` 失败（返回行以 `ERR ` 开头），`2` 参数错误。

> 说明：新勾选的作用域对**目标应用之后新建的进程**生效；已在运行的目标进程需重启（或用 `--force-stop`）才会被注入，这与管理器 App 的行为一致。

## 三、线协议（供二次开发参考）

抽象 socket `@lspd_ctl`，请求为单行 UTF-8、空格分隔、`\n` 结尾；响应首行 `OK`/`ERR <msg>`，随后可有若干数据行，服务端读完即关闭连接。

```
ping
list
enable   <modulePkg>
disable  <modulePkg>
getscope <modulePkg>
scope    <modulePkg> <scopeSpec> <flags>
unscope  <modulePkg> <scopeSpec>
activate <modulePkg> <scopeSpec> <flags>
```

`scopeSpec` = `pkg[/userId]` 逗号分隔（`-` 表示空）；`flags` = `replace,forcestop` 的任意子集（`-` 表示无）。

## 四、构建

```sh
scripts/build.sh          # 两个 flavor 一起构建，产物在 magisk-loader/release/
```

CLI 的服务端（`CommandListener`）在 `daemon.apk` 里，客户端入口（`CliMain`）也在
同一个 apk，因此两个 flavor 的 CLI 完全一致，不存在只有某个 flavor 能用的情况。

环境要求、libxposed 依赖的提交选点（**tag `100` 不是 LSPosed HEAD 需要的那个
`100`**，挑错会直接编译失败）见 [BUILDING.md](BUILDING.md)。

## 五、权限与 SELinux 说明

**调用方身份校验**：`CommandListener` 在每次连接时通过 `LocalSocket.getPeerCredentials()`
读取对端 uid，**只接受 uid 0（root）和 uid 2000（adb shell）**，其余一律返回 `ERR permission denied`；
读不到凭据时同样拒绝（fail closed）。抽象命名空间的 socket 没有文件权限位可依赖，
因此这层校验是必需的——否则一旦 SELinux 在某些 ROM 上放行，普通应用就能自行启用模块并勾选任意作用域。

守护进程（Magisk 启动，`magisk` 域）与 `su` 域之间连接抽象 socket，通常已被 Magisk 内置策略放行；
为稳妥起见，`sepolicy.rule` 追加了 `allow magisk magisk unix_stream_socket connectto`。
若在某些 ROM 上被 SELinux 拦截，可用 `dmesg | grep avc` 观察被拒上下文并据此补充规则。
