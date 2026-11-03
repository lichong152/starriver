# 星川 / Star River TV · 安装说明（执行手册）

这份文件写给「替用户把星川装到电视上」的执行者——人或 AI 代理。请先整篇读完，再动手。

星川是一个 Android TV 桌面（Launcher），包名 `app.xingchuan.tv`，入口 `app.xingchuan.tv/.MainActivity`，
`minSdk 21`、`targetSdk 34`，编译目标 API 36。

---

## 0. 硬性规则（不可跳过）

1. **只做用户明确要求的动作。** 重启、待机、关机、卸载旧版、清空应用数据、停用或禁用原厂桌面，
   都要先问，得到明确同意后才执行。
2. **签名不匹配时先找原签名密钥**（仓库里的 `local/signing.properties`，或用户手上的 keystore），
   确认是不是同一把钥匙；确认不了就问，**不要自动卸载**。
3. 每条 adb 命令都带 `-s <电视IP>:5555`，包括 `install`、`shell`、`push`、`pull`。
4. 第 2 步记录的值是**回滚的唯一依据**，必须原样贴给用户。恢复时只改自己动过的那一项，
   **不要拿旧快照覆盖整份列表**——用户可能在此期间新装带无障碍服务的应用。
5. **没有实际跑过的检查项，不要写成「通过」**。安装、主页键、待机唤醒、开机，四项分开报。
6. 需要用户在电视上按键/确认时，**先提醒，再等**，不要连点、不要替用户按电源键。

---

## 1. 连接电视

```sh
adb connect <TV_IP>:5555
adb devices
```

- 显示 `unauthorized`：电视上弹了「允许 USB 调试吗？」，请用户勾选**「一律允许」**再点允许，然后重试。
- 显示 `refused` / `cannot connect`：ADB 调试没开或端口不对。请用户在
  设置 → 关于 → 连点版本号进开发者选项 → 打开「USB 调试 / 网络调试（ADB）」。
- 部分小米/红米电视每次唤醒会**关掉 ADB**。若中途掉线，请用户重新打开，而不是反复重试。

---

## 2. 记录现场（安装前必做）

```sh
adb -s <TV_IP>:5555 shell getprop ro.build.version.sdk
adb -s <TV_IP>:5555 shell getprop ro.product.manufacturer
adb -s <TV_IP>:5555 shell getprop ro.product.model
adb -s <TV_IP>:5555 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s <TV_IP>:5555 shell settings get secure screensaver_components
adb -s <TV_IP>:5555 shell settings get secure enabled_accessibility_services
adb -s <TV_IP>:5555 shell settings get secure enabled_notification_listeners
```

记下四样东西，原样贴给用户：

| 项目 | 用途 |
|---|---|
| 当前 HOME 组件（通常是原厂桌面，形如 `com.xxx.tvlauncher/.MainActivity`） | 回滚时设回去；也用来判断「主页键是否被抢走」 |
| 当前屏保 `screensaver_components` | **星川不注册屏保、不修改屏保**，记下来只是为了出问题时能证明与星川无关 |
| 当前无障碍服务列表 `enabled_accessibility_services` | 开启守护要往这份列表里**追加**一项，不能覆盖 |
| 当前通知监听 `enabled_notification_listeners` | 星川不注册通知监听，记下来同理 |

SDK 版本低于 28（Android 9）时，守护服务与部分窗口行为可能不生效，请提前告诉用户「本机只能装标准形态，
守护不可靠」，不要事后才发现。

---

## 3. 安装

星川有两个形态，功能完全相同，差别只在清单里有没有 `HOME` 声明：

| 形态 | 文件 | 适用 |
|---|---|---|
| standard | `app-standard-release.apk` | 默认选它。声明自己是桌面，能被系统设成默认 HOME |
| guard | `app-guard-release.apk` | 固件**拒装声明 HOME 的 APK** 时用（TCL/雷鸟、部分海信等）。装上去是个普通应用，靠守护服务常驻前台 |

```sh
adb -s <TV_IP>:5555 install -r app-standard-release.apk
```

失败时按下面处理，**不要自行卸载**：

- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`
  → 电视上已有一个**签名不同**的星川。先比对签名：

  ```sh
  adb -s <TV_IP>:5555 shell pm path app.xingchuan.tv
  adb -s <TV_IP>:5555 shell dumpsys package app.xingchuan.tv | grep -iE "versionName|firstInstallTime|lastUpdateTime"
  ```

  本机 APK 的签名：`apksigner verify --print-certs app-standard-release.apk`（用 SDK 的 build-tools）。
  两边的 SHA-256 一致才可以覆盖升级；不一致就先问用户：是找回原 keystore，还是同意卸载旧版重装。
- `INSTALL_FAILED_VERIFICATION_FAILURE`
  → 固件的安装校验拦截了 adb 安装（TCL 常见）。改用 guard 形态，让用户从电视自己的文件管理器装：

  ```sh
  adb -s <TV_IP>:5555 push app-guard-release.apk /sdcard/Download/xingchuan-guard.apk
  ```

  然后提醒用户在电视上打开「媒体中心 / 文件管理 → Download → xingchuan-guard.apk」安装。
- `INSTALL_FAILED_INSUFFICIENT_STORAGE` → 请用户清理空间，不要擅自删应用。

安装成功后确认：

```sh
adb -s <TV_IP>:5555 shell pm path app.xingchuan.tv
```

---

## 4. 设为默认桌面

standard 形态。先争取 HOME 角色（部分固件没有 `cmd role`，这行报错可忽略）：

```sh
adb -s <TV_IP>:5555 shell cmd role add-role-holder --user 0 android.app.role.HOME app.xingchuan.tv
adb -s <TV_IP>:5555 shell cmd package set-home-activity app.xingchuan.tv/.MainActivity
adb -s <TV_IP>:5555 shell input keyevent KEYCODE_HOME
```

- 如果 `set-home-activity` 返回 `Error: ...` 或没反应，请用户手动设置：
  **设置 → 应用 → 默认应用 → 主屏幕 → 星川**（不同品牌路径略有差异）。

- **注意：`set-home-activity` 返回 `Success` 不等于主页键会到星川。**
  在部分国产固件（实测 Android 9 / `com.stv.*` 平台）上，命令确实写入了系统偏好，
  但厂商的守护进程会用**显式组件**拉起自己的桌面，绕过 HOME 解析。判断方法：

  ```sh
  adb -s <TV_IP>:5555 shell "dumpsys package | sed -n '/Preferred Activities/,/Category/p'"
  # 期望看到 app.xingchuan.tv/.MainActivity 且 mAlways=true → 系统偏好已经是星川
  adb -s <TV_IP>:5555 shell "dumpsys activity activities | grep -m1 'cat=\[android.intent.category.HOME\]'"
  # 若这行的 intent 里带 pkg=厂商包名 cmp=厂商包名/... → 主页键被厂商写死，转第 6 节
  ```

  此时 `cmd package resolve-activity` 仍会报旧桌面，属正常现象，不代表设置失败。
- 第一次按主页键，系统通常弹「使用哪个应用打开」：请用户选**星川**并勾选**「始终」**。
- 用户按 Home 后如果还是原厂桌面，看第 6 节（守护）。

**不要禁用原厂桌面。** 星川不需要、也不应该靠冻结厂商应用来抢桌面。

---

## 5. 验证（逐项做，逐项报）

四项分开记录，任何一项没做就说没做。

### 5.1 安装

```sh
adb -s <TV_IP>:5555 shell pm path app.xingchuan.tv
adb -s <TV_IP>:5555 shell dumpsys package app.xingchuan.tv | grep -m3 versionName
```

### 5.2 主页键 + 界面

```sh
adb -s <TV_IP>:5555 shell input keyevent KEYCODE_HOME
adb -s <TV_IP>:5555 shell "dumpsys window | grep mCurrentFocus"
adb -s <TV_IP>:5555 shell screencap -p /sdcard/xingchuan-check.png
adb -s <TV_IP>:5555 pull /sdcard/xingchuan-check.png xingchuan-check.png
```

**拉回截图自己看一眼**再下结论：图标是不是真实应用图标、Dock 有没有溢出、时钟对不对。
只看命令返回码不算验证。

### 5.3 待机唤醒

请用户按遥控器电源键待机，再按一次唤醒（**不要用 adb 发电源键，会真的关机**）。
唤醒后重复 5.2 的 focus 与截图，确认仍是星川。

### 5.4 开机

**必须先问用户**能否重启电视。同意后再 `adb reboot`，等开机完成（`adb wait-for-device` +
`getprop sys.boot_completed`），然后重复 5.2。
标准形态应该由系统直接拉起；guard 形态依赖守护服务或用户按一次主页键。

---

## 6. 桌面守护（可选，默认关闭）

**为什么需要**：固件在开机、唤醒、按 Home 时可能绕过用户选定的桌面，直接把自己的桌面拉到前台。
系统没有给第三方应用「夺回 HOME」的普通接口，星川用无障碍服务监听窗口切换，发现原厂桌面回到前台就切回来。

**它能看到什么**：只监听「前台切换到了哪个包」，`canRetrieveWindowContent=false`，读不到屏幕内容。

### 开启

**状态的唯一来源是系统无障碍开关**：勾选了星川桌面守护 = 守护生效。应用内的开关只负责「关闭」。

电视上开启：按**菜单键** → 设置 → 焦点移到**守护·关** → 按确定。会跳到系统无障碍设置，
请用户勾选**「星川桌面守护」**并允许。回到桌面后显示**守护·开**才算生效。

命令行等价做法（**追加，不要覆盖**）：

```sh
adb -s <TV_IP>:5555 shell settings get secure enabled_accessibility_services
# 把上面返回值（可能为 null、可能是 a:b 形式）拼上 :app.xingchuan.tv/app.xingchuan.tv.core.GuardService
# 原值为 null 时才可以只写这一项：
adb -s <TV_IP>:5555 shell settings put secure enabled_accessibility_services app.xingchuan.tv/app.xingchuan.tv.core.GuardService
adb -s <TV_IP>:5555 shell settings put secure accessibility_enabled 1
adb -s <TV_IP>:5555 shell "dumpsys accessibility | grep label="   # 出现 Service[label=星川桌面守护] 才算绑定成功
```

绑定成功后连按三次主页键验证，每次 `mCurrentFocus` 都应是星川，且在 1.5 秒内完成切换。

Android 13+ 侧载应用的无障碍服务默认被「受限设置」挡住，需要在电视上
**设置 → 应用 → 星川 → 右上角菜单 → 允许受限设置**，再回来勾选。

### 适用与风险

- 只在「第 4 步做完、主页键仍被抢」时才建议开启；单纯装 standard 形态通常不需要。
- 个别固件会与守护服务互抢前台，表现为桌面闪一下。出现时先关守护，再反馈。
- 关掉守护要两步都做：设置面板切回「守护·关」**并且**在无障碍里取消勾选。
  应用内切关会立刻停止拉前台；无障碍不取消则服务仍绑定（但不再动作）。
- 实测机型（Android 9 / 192.168.0.2）：开启后连按三次主页键均在 1.5 秒内回到星川，
  无来回抢焦点，`logcat` 无 ANR / crash。

---

## 7. 保持 ADB 常开（本版**未实现**）

星川没有内置这个开关，也不申请 `WRITE_SECURE_SETTINGS`。

- 小米/红米等每次唤醒会关闭 ADB 的机型，需要用户自己在
  **开发者选项 → USB 调试**里重新打开，后续才能继续用 adb 升级。
- 不要为了「保持 ADB」去 `settings put global adb_enabled 1` 之类的全局改写——
  这类命令会影响整机调试开关，属于超出授权范围的改动。

---

## 8. 回滚

按严重程度从轻到重，**每步都要先问用户**。

1. 关守护（如果开过）：设置面板把「守护·开」切回关；或把第 2 步记录的无障碍列表原样写回，
   只删掉 `app.xingchuan.tv/app.xingchuan.tv.core.GuardService` 这一项，其余保留。
2. 设回原厂桌面：

   ```sh
   adb -s <TV_IP>:5555 shell cmd package set-home-activity <第 2 步记录的 HOME 组件>
   adb -s <TV_IP>:5555 shell input keyevent KEYCODE_HOME
   ```

   确认前台变回原厂桌面（`dumpsys window | grep mCurrentFocus`）。
3. 卸载星川（**需用户明确同意**）：

   ```sh
   adb -s <TV_IP>:5555 uninstall app.xingchuan.tv
   ```

星川不会改屏保、不会注册通知监听、不会禁用任何应用，所以回滚只需处理 HOME 与无障碍两项。

---

## 9. 已知限制

- **壁纸选文件**依赖系统文件选择器（SAF）。不少电视固件没有 DocumentsUI，此时只能在设置里选内置渐变；
  选图片/视频会提示「本机没有文件选择器」。这是固件缺失，不是安装失败。
- Dock 上限 7 个应用 + 1 个「全部应用」；推荐位 5 个，按启动频次排序。
- 隐藏应用后只能通过「恢复隐藏」一次性恢复，没有逐个恢复入口。
- 守护服务已在 Android 9 实机验证主页键场景；**待机唤醒、开机自启两个场景未验证**（需要重启/待机，
  必须事先征得同意），首次开机建议在旁边观察一次。
- 桌面不会读取任何账户、不联网（除了后续自建更新通道，本版没有）。

---

## 10. 验收：把原始输出贴给用户

按顺序执行，**原始输出照抄**，不要改写、不要只写「正常」：

```sh
adb -s <TV_IP>:5555 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s <TV_IP>:5555 shell settings get secure enabled_accessibility_services
adb -s <TV_IP>:5555 shell input keyevent KEYCODE_HOME
# 等待两秒
adb -s <TV_IP>:5555 shell "dumpsys window | grep mCurrentFocus"
```

判定：

- 第一条的组件若是 `app.xingchuan.tv/.MainActivity`，说明 HOME 解析到星川；
  guard 形态下仍可能是原厂组件，属正常。
- 第三条输出里应出现 `app.xingchuan.tv/app.xingchuan.tv.MainActivity`；
  若还是原厂桌面，说明 Home 角色没拿到或需要开守护——如实说明，不要圆场。

最后给用户一张结论表（通过 / 未通过 / 未测），四项：安装、主页键、待机唤醒、开机。

### 实测记录 · 2026-11-03 · `192.168.0.2:5555`

机型 `Droidlogic ak30a4`，Android 9（SDK 28），1920×1080。

| 现场项 | 安装前记录值 |
|---|---|
| 默认 HOME | `com.letv.lekids/.burrow.ExternalBurrowActivity` |
| 屏保 | 未设置 |
| 无障碍 | `null` |
| 通知监听 | 未设置 |
| ADB 调试 | `adb_enabled=1`（已常开） |

| 验收项 | 结果 | 依据 |
|---|---|---|
| 安装 | 通过 | `install -r` → `Success`；`pm path` 有返回 |
| 主页键 | 通过（**依赖守护**） | 未开守护时落到 `com.stv.deskplatform`；开守护后连按 3 次均在 1.5 秒内回到 `app.xingchuan.tv` |
| 待机唤醒 | 未测 | 需要用户按遥控器电源键，未执行 |
| 开机 | 未测 | 需要重启，未征得同意 |

系统偏好写入成功（`mAlways=true`），但主页键被厂商显式组件劫持，这是本机型必须开守护的原因。

---

## 附：一键脚本

`tools/install.sh` 把「连接 → 记录 → 安装 → 设桌面 → 验证」串起来，每一步都会打印原始输出：

```sh
TV=<电视IP> tools/install.sh            # standard
TV=<电视IP> FORM=guard tools/install.sh # guard
```

脚本**不会**重启、卸载、清空数据或停用原厂桌面；需要时由人按上面各节手动执行。
