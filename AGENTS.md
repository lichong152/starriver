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
- 守护服务已在 Android 9 实机验证三个场景：主页键、开机自启（2026-11-03）、待机唤醒（2026-11-07，
  电源键待机后唤醒前台仍是星川）。仍需留意：部分小米/红米固件每次唤醒会关掉 ADB。
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
| 开机 | 通过 | 用户重启后直接进入星川桌面，无需按任何键（BootReceiver + 守护均在） |

系统偏好写入成功（`mAlways=true`），但主页键被厂商显式组件劫持，这是本机型必须开守护的原因。

### 实测记录 · v1.0.1 · 2026-11-07

v1.0.1 相比 v1.0.0 只有构建侧变化（R8 混淆 + 壁纸预缩）和四处代码修复，
所以**重点验证混淆后的产物是否仍可运行**，其余行为沿用上表。

| 验收项 | 结果 | 依据 |
|---|---|---|
| 覆盖安装 | 通过 | `install -r` → `Success`，版本 `1.0.1`，数据与签名沿用，无需卸载 |
| 启动与渲染 | 通过 | 截图核对：时钟/日期、18 个真实图标、风光壁纸、Dock 满行带标签、设置面板 14 格（两行）无溢出 |
| 组件未被混淆裁掉 | 通过 | `MainActivity` / `GuardService` / `BootReceiver` 类名在 manifest 与 dex 中均原样保留 |
| 主页键 | 通过（**依赖守护**） | 连按 3 次，每次 `mResumedActivity` 均为 `app.xingchuan.tv/.MainActivity` |
| 守护绑定 | 通过 | 覆盖安装后 `enabled_accessibility_services` 仍含 `GuardService`、`accessibility_enabled=1`，设置面板显示「守护·开」 |

覆盖安装后前台会落回厂商桌面（`com.stv.deskplatform`），因为安装过程本身不产生窗口切换事件、
守护不会触发；脚本会用 `am start` 拉一次，属预期行为，不是缺陷。

### 实测记录 · v1.0.3 · 2026-11-07

壁纸从单组 6 张扩为四组各 10 张，间隔改 30 秒，设置面板由一行 11 格改为两行 14 格。

| 验收项 | 结果 | 依据 |
|---|---|---|
| 四组切换 | 通过 | 设置面板依次选城市夜色 / 星空银河 / 山野光影，关面板后前台立即换成对应组首图 |
| 组内轮播 | 通过 | 32 秒间隔连拍两次，画面从「森林河湾」换到「林间瀑布」，证明 30 秒定时生效 |
| 设置面板两行 | 通过 | 14 格分 7+7 等分铺满面板，无截断、无溢出；左右同行走动、上下跨行 |
| 旧配置兼容 | 通过 | v1.0.2 存的 `nature:auto` 升级后仍落在「自然风光」组，没有回退成渐变 |
| 覆盖安装 | 通过 | `install -r` → `Success`，版本 `1.0.3`，壁纸选择与守护状态均保留 |

### 实测记录 · v1.1.2 · 2026-11-07

这一版是「复查 + 修补」，不改界面风格，重点是把几处会静默失效的地方补上。

| 验收项 | 结果 | 依据 |
|---|---|---|
| 抽屉应用操作菜单 | 通过 | 抽屉内按菜单键 → 弹层显示应用名 + 包名 + 三项动作（加入 Dock / 在桌面隐藏 / 取消），动作文案随 Dock 状态切换 |
| 隐藏 → 恢复闭环 | 通过 | 隐藏后抽屉标题由 18 个变 17 个、Toast 提示「在设置 → 恢复隐藏里可取回」；设置面板第二行显示「恢复隐藏 1」，执行后回到无数字、抽屉恢复 18 个 |
| 加入 Dock | 通过 | 对不在 Dock 的应用执行「加入 Dock」，回主界面后该应用出现在 Dock 末尾 |
| Dock 满提示 | **未实测** | 代码里 `toggleDock` 返回 false 时弹「Dock 已满（最多 7 个）」；真机未把 Dock 填到 7 个再试第 8 个 |
| 壁纸失效回退 | **未实测** | 图片/视频解码失败时回退到内置「自然风光」并 Toast；没有构造失效的 URI 样本 |
| 选择图片 / 视频 | 通过 | 设置面板「选择图片」拉起 `com.android.documentsui/.picker.PickActivity`，正常列出最近图片；说明该机型不是「无文件选择器」的固件 |
| 时钟不重组整棵树 | 通过（间接） | 时钟状态移入 `TopBar`，HomeScreen 不再持有 `now`；连续观察 12 秒以上无异常、无掉帧现象，logcat 无异常 |
| 覆盖安装 | 通过 | `install -r` → `Success`，版本 `1.1.2`，Dock / 壁纸 / 守护状态全部保留 |

两条这一轮才确认的工程事实，值得记下来：

1. **`Modifier.blur` 在 API 31 以下完全无效**。本机是 Android 9（SDK 28），此前
   `blur(6.dp)`／`blur(18.dp)` 一直没生效，壁纸其实是清晰的锐利原图。已删除这两处
   空调用并把 `Glass.kt` 里「壁纸已预处理模糊」的错误注释改正——想真要磨砂效果，
   只能解码时降采样再放大，不能指望这个 API。
2. **logcat 里的 `ClassNotFoundException: android.view.HardwareCanvas` 不是崩溃**。
   它来自 Compose 在部分深度定制 ROM 上的绘制降级（框架层隐藏类被裁），
   catch 后回落到普通 `Canvas`，级别是 `I`，应用继续运行；不要把它当作线上故障排查。


### 实测记录 · v1.2.0 · 2026-11-07

这一版把顶栏空转的「信号源」换成能用的动作，并补上 Dock 排序与背景柔焦。

| 验收项 | 结果 | 依据 |
|---|---|---|
| 壁纸胶囊切组 | 通过 | 主屏按上键两次进入顶栏，确认后壁纸从「自然风光」切到「城市夜色」，胶囊高亮 |
| Dock 内排序 | 通过 | 抽屉里对「当贝市场」执行「向右移一位」，菜单保持打开可连续挪动；关闭后 Dock 顺序变为 超级电视账号 / 当贝音乐 / 当贝市场 / 儿童TV / 高清直播，重启后仍保持 |
| 柔焦背景 | 通过 | 截图 4 倍放大核对：背景建筑边缘发虚、前景「星期六」文字锐利；PNG 体积从 2.8MB 降到 1.7~2.1MB，说明高频细节确实减少 |
| 设置三行布局 | 通过 | 15 格 5+5+5 等宽等分，无截断；上/下跨行、左/右同行走动正常 |
| 覆盖安装 | 通过 | `install -r` → `Success`，版本 `1.2.0`，Dock / 壁纸 / 守护状态保留 |
| 待机唤醒 | 通过 | 用户按遥控器电源键待机约 5 秒再唤醒；`dumpsys activity` 的 `mResumedActivity` 与 `dumpsys window` 的 `mCurrentFocus` 都是 `app.xingchuan.tv/.MainActivity`，守护没让前台落到厂商桌面 |
| 发布 | 通过 | Release v1.2.0 已发；GitHub 侧资产 digest 与本地一致（standard `7550edf5…`、guard `339f34c5…`）；官网同步更新 |

两点实现说明：

- 柔焦不是模糊滤镜，而是「解码时缩到 1/4 屏宽，显示时再放大」。用 `inSampleSize` 只能按 2 的幂取，
  各组原图宽度不同（1920 / 2560）会导致柔化程度不一致，所以再叠一层 `inDensity/inTargetDensity`
  把解码宽度精确压到目标值——四组观感才统一。
- 「信号源」胶囊被替换掉的原因记在 v1.1.0 段：本机 TvInputService 没有可被第三方调起的 Activity，
  切换输入需要系统签名权限，留着只会是个点了弹提示的假按钮。


### 实测记录 · v1.2.1 · 2026-11-07

用户反馈「很白的壁纸让透明背景显示不出来」。查下来是 `nature-09`（罂粟花田）平均亮度 210/255、
天空占了大半张，玻璃面板本就是白色半透明，压上去等于没有；顺带把其余偏高亮的素材一起收了。

| 验收项 | 结果 | 依据 |
|---|---|---|
| 素材亮度复核 | 通过 | 40 张逐张算平均亮度：`nature-09` 210、`nature-08` 176、`zen-06` 165、`zen-10` 151。前三张压暗到 131 左右，`nature-09` 压暗后发灰，换成林间栈桥（48） |
| 玻璃面板可读性 | 通过 | 换素材后连续截 3 帧（间隔 30 秒）核对：胶囊、推荐位卡片、Dock 的描边与内层渐变都清晰，白色文字有足够对比 |
| 亮度自适应遮罩 | **部分验证** | 代码按画面平均亮度加深遮罩（16×9 采样，1.5 秒过渡）。内置素材最高 143，属轻微加深区间；纯白自选照片的主观效果未实测 |
| 覆盖安装 | 通过 | `install -r` → `Success`，版本 `1.2.1`（versionCode 15），首帧 `Displayed +1s102ms`，logcat 无异常 |

实现要点：亮度在解码时顺手算好（`Bitmap.createScaledBitmap` 到 16×9 再取平均），
缓存进 `GroupFrames`，换图时经 `LaunchedEffect` 报给壁纸层；遮罩三档 alpha 由 `boost` 线性加深，
最高从 (0.44/0.22/0.62) 提到 (0.56/0.48/0.72)。渐变壁纸不采样，用默认值。


### 实测记录 · v1.2.2 · 2026-11-07

用户提了四件事：Dock 应用名显示不全、时钟要带秒、壁纸太糊且要 10 秒一换、加几套待机屏保。

| 验收项 | 结果 | 依据 |
|---|---|---|
| 时钟带秒 | 通过 | `TopBar` 每秒刷新并对齐整秒（`delay(1000 - millis)`）；整串 `HH:mm:ss` 同字号一次排版，`fontFeatureSettings="tnum"` 让数字等宽——实测 `14:09:39` → `14:09:46` 时右侧日期起点都在 x=714，版面不抖 |
| Dock 应用名不截断 | 通过 | `DockTile` 改为 `maxLines = 2` + 按字数缩字号（≤4 字 12sp / ≤6 字 11sp / 更长 10sp），行高 1.15 倍；「超级电视账号」等长名称两行完整显示 |
| 壁纸清晰度 | 通过 | 柔焦解码宽度 `full/4`（480）→ `full*2/3`（1280），截图对比山脊与云层纹理明显回来；设置里关掉柔焦即 100% 原图 |
| 10 秒换图 | 通过 | `GROUP_INTERVAL_MS` 30s → 10s |
| 设置四行布局 | 通过 | 新增「屏保」后 16 格，`PER_ROW` 5 → 4，四行等宽不出「末行落单」 |
| 屏保注册 | 通过 | 切到「屏保·大时钟」后 `settings get secure screensaver_components` = `app.xingchuan.tv/.ScreensaverService`，`screensaver_enabled=1` |
| 屏保画面 | 通过 | 把 `screen_off_timeout` 临时调到 12 秒触发 `mWakefulness=Dreaming` 后逐个截图：星空居中、壁纸幻灯左下角、极简右下角带秒、大时钟同星空同款居中布局；四套均无裁切 |
| 屏保时钟截断（修复） | 通过 | 第一版星空只显示「14」、日期整体偏左。原因是两个 TextView 竖排在 `LinearLayout` 里、靠父容器的 `WRAP_CONTENT + gravity` 定位，这台 ROM 把宽度算错。改成单个 TextView 内 `HH:mm` + `\n` + 日期、日期用 `RelativeSizeSpan` 缩小，定位交给 TextView 自身 `gravity`，四套样式复测正常 |
| 覆盖安装 | 通过 | `install -r` → `Success`，版本 `1.2.2`（versionCode 16） |

三件实现上的事（外加一条布局踩坑）：

1. **屏保用原生 View 而不是 Compose**。`DreamService` 没有现成的 `LifecycleOwner`，在 Android 9 上
   挂 `ComposeView` 要手工补 `ViewTreeLifecycleOwner` / `SavedStateRegistryOwner`，屏保又只是
   「一块会动的画」，原生 View + 每秒改一次 TextView 更稳。
2. **写系统屏保要 `WRITE_SECURE_SETTINGS`**，侧载后必须
   `adb shell pm grant app.xingchuan.tv android.permission.WRITE_SECURE_SETTINGS`。
   没授权时样式仍会存在本地（`starriver.xml` 的 `screensaver_style`），但按确认会 Toast 提示去授权。
   接管时会把原来的组件记进 `screensaver_prev_component`，选「关闭」就还回去。
3. **`settings put secure screen_off_timeout` 在这台机器上是 `2147483647`（永不待机）**，
   所以屏保不会自己出现；台面上只有两种触发方式：遥控器电源键，或临时把超时调短。
   另外**不能用 `input keyevent 223` 测**：它会把 adb 端口一起睡掉，`screencap` 只拿到 0 字节。
   正确做法是 `settings put system screen_off_timeout 12000` → 等 16 秒 → 截图 → 立刻改回 `600000`。

**布局踩坑**：这台 ROM 上把 `WRAP_CONTENT` 的 `LinearLayout` 塞进 `FrameLayout` 再用
`Gravity.CENTER` 定位，子项宽度会被算错——星空屏保的时钟只剩「14」、日期整体偏左。
屏保这类「两三行字」的版式不要套容器：单个 `TextView`，行间用 `\n`，字号差用
`RelativeSizeSpan`，定位交给 `TextView` 自己的 `gravity`，一次到位。


---

## 附：一键脚本

**日常更新只用这一条**（构建 + 覆盖安装 + 校验版本/前台/守护）：

```sh
TV=<电视IP> tools/update-tv.sh              # 构建并安装 standard
TV=<电视IP> FORM=guard tools/update-tv.sh   # 装 guard 形态
TV=<电视IP> SKIP_BUILD=1 tools/update-tv.sh # 只装现成 APK，不重新构建
```

电视离线时脚本会等 60 秒并提示先开机（「小爱同学，打开电视」或遥控器电源键），
开机后再跑一次即可。`install -r` 保留数据，不需要卸载。

首次安装用 `tools/install.sh`，它把「连接 → 记录 → 安装 → 设桌面 → 验证」串起来，
每一步都会打印原始输出：

```sh
TV=<电视IP> tools/install.sh            # standard
TV=<电视IP> FORM=guard tools/install.sh # guard
```

两个脚本都**不会**重启、卸载、清空数据或停用原厂桌面；需要时由人按上面各节手动执行。
