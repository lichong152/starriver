# 星川 · Star River TV

一个自用的 Android 电视桌面。视觉走 tvOS 27 的 **Liquid Glass** 路线（毛玻璃 Dock、大圆角、
纵向层次），工程结构按国产电视的现实约束来写（minSdk 21、LEANBACK/LAUNCHER 双入口、
standard / guard 双形态）。

参考了两个开源项目，各取所长：

| 参考 | 取用的部分 |
| --- | --- |
| [xinghe-desktop](https://github.com/youshoubianyouni/xinghe-desktop) | Compose 技术选型、minSdk 21 与依赖版本组合（AGP 8.12 / Kotlin 2.2 / Compose BOM 2025.11）、standard/guard 双 flavor 思路、视频壁纸 |
| [Tigris](https://github.com/lesnolie/Tigris) | 液态玻璃的视觉语言、玻璃 Dock + Top Shelf 的信息层级、PackageManager 枚举与中文排序 |

代码是重新写的，没有复制上述项目的源文件；包名、资源、交互均为本项目自有实现。

![界面预览](design/preview.png)

预览页 `design/preview.html` 按 1920×1080 实际像素还原版式，可直接用浏览器打开比对。

## 特性

- **真实应用图标**：从 `PackageManager` 读各应用自己声明的图标（入口图标优先，其次包图标），
  自适应图标、方形图标、宽 banner 都按比例处理，不会拉变形；只有确实没有图标的应用才回退成首字卡片
- **顶栏状态条**：问候语（按时段变化）+ 大时钟（精确到秒）+ 行内日期，右侧四枚功能胶囊（壁纸 / 联网方式 / 设置 / 关于）。「壁纸」按一下切到下一组内置壁纸。
  胶囊可用遥控器上键进入、左右切换、确认执行；联网方式读本机网卡状态，不申请任何权限
- **玻璃 Dock**：底部圆角玻璃条，末位固定「全部应用」入口，聚焦时放大 + 亮描边
- **推荐位（Top Shelf）**：按启动频次自动排序的大卡片，越用越顺手
- **应用抽屉**：全屏网格，中文拼音排序；菜单键对单个应用弹出操作菜单（加入/移出 Dock、左右移排序、在桌面隐藏），隐藏项可在设置里一键恢复
- **柔焦背景**：内置壁纸按 1/4 屏宽解码再放大显示，得到真正的柔化背景——`Modifier.blur` 在 API 31 以下无效，
  这是低版本上唯一可行且几乎免费的做法；设置里「柔焦」可随时关掉换回锐利原图
- **壁纸**：**四组内置轮播**（自然风光 / 城市夜色 / 星空银河 / 山野光影，每组 10 张共 40 张，10 秒交叉淡入换一张，画面保持静止）
  + 5 套内置渐变 + 本地图片 + 本地视频（静音循环）。图片按 `assets/wallpapers/` 文件名排序播放，
  丢进自己的 jpg 就会自动加入轮播，不用改代码（来源与版权见 [CREDITS.md](CREDITS.md)）
- **亮度自适应遮罩**：玻璃面板是白色半透明的，压在很白的壁纸上会"看不见"。壁纸层会采样当前画面的
  平均亮度，越白遮罩压得越重（换图时 1.5 秒过渡），所以自己换成亮照片也不会糊成一片
- **待机屏保**：四套样式——大时钟 / 壁纸幻灯 / 星空 / 极简，在设置里一键切换，选「关闭」交还系统原屏保。
  只有被系统选为当前屏保时才运行，不常驻、不联网（写入系统屏保需要一次 `adb pm grant`，见下）
- **空闲省电**：不常驻后台服务、不申请多余权限，只做桌面该做的事
- **安装即变**：装/卸应用后自动重扫，不需要重启桌面
- **双形态**：`standard` 声明 HOME 作为系统桌面；`guard` 不声明 HOME，供 TCL / 海信等
  会拦截第三方 HOME 声明的固件以普通应用方式安装
- **桌面守护（可选）**：固件把自家桌面拉回前台时，用无障碍服务立刻切回星川。
  只监听窗口切换、不读屏幕内容，默认关闭，设置面板里一键开关

## 构建

需要 JDK 17 与 Android SDK（`compileSdk 36`、`build-tools 36.0.0`）。

```bash
# 系统桌面形态
./gradlew assembleStandardRelease
# 无 HOME 声明形态（固件拦截时用）
./gradlew assembleGuardRelease
```

产物：

```
app/build/outputs/apk/standard/release/app-standard-release.apk
app/build/outputs/apk/guard/release/app-guard-release.apk
```

未提供 `local/signing.properties` 时，release 会退回到本机 debug 签名 —— 能装，但**不能覆盖升级**
已用其它签名安装的版本。要长期自用，建议先固定签名：

```properties
# local/signing.properties
storeFile=starriver.jks
storePassword=******
keyAlias=starriver
keyPassword=******
```

## 安装

完整的执行步骤（记录现场、安装、设默认桌面、验证、开启守护、回滚）写在
**[AGENTS.md](AGENTS.md)** 里，那是给「替你装电视的人或代理」看的操作手册；
要直接发给别人执行，用 **[INSTALL-PROMPT.md](INSTALL-PROMPT.md)** 里那段复制即用的指令。

最短路径：

```bash
adb connect <电视IP>:5555     # 电视需先打开 ADB 调试
adb install -r app-standard-release.apk
adb shell cmd package set-home-activity app.xingchuan.tv/.MainActivity
adb shell input keyevent KEYCODE_HOME
```

装好后按 Home 键，系统会询问默认桌面，选「星川」并勾选「始终」。

若装完按 Home 没反应，说明固件截住了 HOME 声明，改用 guard 形态安装，再从应用列表打开，
并在设置面板里把「守护·关」切成「守护·开」。

## 实机验证结果（Android 9 / 1920×1080）

| 项 | 结果 |
|---|---|
| 安装 / 升级（`install -r`） | 通过（v1.0.0 首装、v1.0.1 覆盖升级均 Success） |
| 真实应用图标 | 通过（18 个应用图标全部正确显示） |
| 四组壁纸轮播 | 通过（四组均可切换；组内 10 秒自动换图） |
| 设置面板 16 格（四行）/ 应用抽屉 18 项 | 通过（无溢出、无截断） |
| 主页键回到星川 | 通过（**开启守护后**；未开守护时被厂商桌面接管） |
| 开机自启 | 通过（重启后直接进入星川，无需按键） |
| R8 混淆产物（v1.0.1） | 通过（组件类名保留、界面与图标渲染正常） |
| 顶栏四胶囊（v1.1.0） | 通过（上键进入、左右切换、确认执行；「设置」开面板、「关于」开信息层、「有线网络」跳到系统设置首页，网络状态显示「有线网络」） |
| 抽屉应用操作菜单（v1.1.2） | 通过（菜单键弹层 → 加入 Dock / 隐藏 / 取消；隐藏后抽屉从 18 变 17、设置里显示「恢复隐藏 1」、恢复后回到 18） |
| Dock 已满提示（v1.1.2） | 逻辑已加（`toggleDock` 返回 false → Toast）；**未在真机跑满 7 个的场景** |
| 壁纸失效回退（v1.1.2） | 代码已加（图片/视频解码失败 → 内置壁纸 + 提示）；**未构造失效样本实测** |
| 选择图片 / 选择视频（v1.1.2） | 通过（本机有 `com.android.documentsui`，设置里选「选择图片」能正常拉起文件选择器并列出最近图片） |
| 壁纸胶囊切组（v1.2.0） | 通过（顶栏左键到「壁纸」，确认后从自然风光切到城市夜色） |
| Dock 内排序（v1.2.0） | 通过（对「当贝市场」执行「向右移一位」，回主界面后它排到了「当贝音乐」之后，顺序持久化） |
| 柔焦背景（v1.2.0） | 通过（截图 4 倍放大核对：建筑边缘发虚、前景文字锐利；设置里「柔焦·开/关」可切回原图） |
| 设置三行布局（v1.2.0） | 通过（15 格 5+5+5 等分，无截断，上下跨行导航正常） |
| 过曝壁纸（v1.2.1） | 通过（原 `nature-09` 罂粟花田平均亮度 210，玻璃面板与白字被吃掉；换成林间栈桥 48，另三张压到 131~135；复测轮播多张，胶囊、卡片、Dock 轮廓均清晰） |
| 亮度自适应遮罩（v1.2.1） | 代码已加（按画面平均亮度加深遮罩，1.5 秒过渡）；**未用纯白自选照片实测**，内置素材最高 143，属遮罩轻微加深的区间 |
| 时钟带秒（v1.2.2） | 通过（显示为 `14:09:23`，秒与分钟同字号、冒号分隔；数字等宽，秒跳变时右侧日期不移动） |
| Dock 应用名不截断（v1.2.2） | 通过（改为最多两行 + 按字数缩字号，「超级电视账号」等长名称完整显示） |
| 壁纸清晰度（v1.2.2） | 通过（柔焦解码宽度由 1/4 提到 2/3，山脊与云层纹理可见；10 秒换一张） |
| 屏保注册（v1.2.2） | 通过（设置里切样式后，`settings get secure screensaver_components` 变为 `app.xingchuan.tv/.ScreensaverService`，`screensaver_enabled=1`） |
| 屏保画面（v1.2.2） | 通过（把 `screen_off_timeout` 临时调到 12 秒触发待机，四套样式逐个截图：大时钟/星空居中、壁纸幻灯左下角、极简右下角；均无裁切） |
| 待机唤醒（v1.2.0） | 通过（遥控器电源键待机后唤醒，`mResumedActivity` 与 `mCurrentFocus` 均为 `app.xingchuan.tv/.MainActivity`） |

这台电视的固件（`com.stv.*`）会用**显式组件**启动自家桌面，绕过 HOME 解析，
所以 `set-home-activity` 虽然 `Success`、系统偏好也写成星川（`mAlways=true`），主页键仍然去了厂商桌面。
唯一非破坏性解法是开守护，详见 [AGENTS.md](AGENTS.md) 第 6 节。

## 遥控器操作

| 键 | 主屏 | 应用抽屉 | 设置 |
| --- | --- | --- | --- |
| 左右 | 顶栏胶囊 / 推荐位 / Dock 内移动 | 网格内移动 | 设置项之间移动 |
| 上 | Dock → 推荐位 → 顶栏 | 首行再按上：收起抽屉 | — |
| 下 | 顶栏 → 推荐位 → Dock / Dock 再按下：打开抽屉 | 向下一行 | — |
| 确定 | 启动 / 执行胶囊 | 启动 | 执行当前设置项 |
| 菜单 | 打开设置 | 打开应用操作菜单（Dock / 隐藏） | 关闭 |
| 返回 | 不响应（避免弹回厂商桌面） | 关闭抽屉 | 关闭 |

## 更新到电视

改完代码后一条命令推到电视（构建 → `install -r` 覆盖安装 → 校验版本/前台/守护）：

```sh
TV=192.168.0.2 tools/update-tv.sh
```

- `FORM=guard` 装守护形态；`SKIP_BUILD=1` 只装现成 APK
- 电视关着时脚本会等 60 秒并提示先开机（「小爱同学，打开电视」或遥控器电源键），开机后再跑一次
- `install -r` 保留数据，不需要卸载；脚本不重启、不清数据、不动原厂桌面

首次安装见 [AGENTS.md](AGENTS.md)，或 `TV=<IP> tools/install.sh`。

## 结构

```
app/src/main/java/app/xingchuan/tv/
├── MainActivity.kt          入口、全屏、装/卸应用后重扫
├── core/
│   ├── Apps.kt              应用枚举（LEANBACK + LAUNCHER 去重）、图标解码、启动
│   ├── Icons.kt             图标解码与 LruCache（入口图标 → 包图标，宽图留边）
│   ├── Guard.kt             桌面守护：无障碍服务 + 开关状态
│   ├── BootReceiver.kt      开机自启（guard 形态用，尽力而为）
│   ├── Shortcuts.kt         顶栏胶囊的系统动作 + 本地联网方式判断
│   └── LauncherModel.kt     设置持久化 + 桌面状态（Dock / 隐藏 / 频次 / 壁纸）
└── ui/
    ├── Home.kt              主屏：竖向层次、焦点分发、Dock、推荐位
    ├── TopBar.kt            顶栏：问候语、时钟、日期、四枚功能胶囊及 Canvas 图标
    ├── About.kt             关于面板（版本 / 设备 / 守护 / 壁纸授权）
    ├── Drawer.kt            应用抽屉
    ├── Settings.kt          设置面板 + 壁纸选择器
    ├── Wallpaper.kt         渐变 / 图片 / 视频壁纸层
    ├── Glass.kt             玻璃面板、焦点缩放、图标兜底
    └── Theme.kt             配色与字阶
├── ScreensaverService.kt 待机屏保（DreamService，四套样式）
```

**焦点为什么不用 Compose 自带的焦点搜索**：电视上 D-pad 的行为必须可预测。这里整屏只有一个
focusable 锚点，所有方向键在 `Home.kt` 里按 index 集中分发，网格滚动用 `animateScrollToItem`
跟随。代价是放弃了无障碍遍历，收益是任何机型上按键都不会“跳飞”。

## 改成你自己的

1. 应用名：`app/src/main/res/values/strings.xml` 的 `app_name`
2. 包名：`app/build.gradle.kts` 的 `applicationId` 与 `namespace`，同时重命名
   `app/src/main/java/app/xingchuan/tv` 目录并全局替换 `app.xingchuan.tv`
3. 图标 / 横幅：`res/drawable/ic_launcher_foreground.xml`、`res/drawable/banner.xml`
4. 主题色：`ui/Theme.kt` 的 `Ink`；壁纸预设：`core/LauncherModel.kt` 的 `Presets`

## 已知限制

- 内置 40 张壁纸（四组各 10 张）来自 Unsplash（Unsplash License，可商用、无需署名、可随 APK 分发）；换成自己的照片见 `CREDITS.md`。
- **图片/视频壁纸依赖系统文件选择器**（SAF）。部分电视固件没有 DocumentsUI，此时会提示改用
  内置壁纸。参考项目用局域网手机上传页解决，本项目暂未实现。
- **背景柔焦**在解码阶段完成（背景图先解码到屏幕 2/3 宽再放大），不依赖 `Modifier.blur`，
  因此 Android 9 这类低版本也有柔化效果；设置里可关掉换回 100% 原图。
- **屏保要写系统设置**：把星川屏保设成当前屏保用的是 `screensaver_components`，需要
  `WRITE_SECURE_SETTINGS`。侧载安装后执行一次：
  `adb shell pm grant app.xingchuan.tv android.permission.WRITE_SECURE_SETTINGS`。
  没授权时设置里仍可切换样式（存本地），但按确认会提示需要授权。
- **桌面守护已在 Android 9 实机验证三个场景**：主页键（连按 3 次均在 1.5 秒内回到星川，无闪跳）、
  开机自启（2026-11-03 重启后直达星川，无需按键）、待机唤醒（2026-11-07 电源键待机再唤醒，前台仍是星川）。
- 未实现：去广告、手机远程传壁纸、天气、「保持 ADB 常开」开关。
- `targetSdk 34`，侧载自用，不上架。

## 官网

`site/` 是项目主页（纯静态，无依赖、无统计脚本、无 Cookie）：需求样式参考 Tigris 官网，
内容为本项目真实资料——真机截图、实机验收记录、AGENTS.md 的安装与回滚流程。

- 本地预览：直接打开 `site/index.html`
- 改完部署：`site/` 整目录上传到任意静态托管即可（EdgeOne Pages / GitHub Pages / Cloudflare Pages）
- 两个 APK（各约 21 MB）发布在 [GitHub Releases](https://github.com/lichong152/starriver/releases)，
  页面下载按钮指向 `releases/latest/download/<文件名>`，发新版本后自动生效，不需要改页面

## 下载

| 形态 | 文件 | 适用 |
|---|---|---|
| 标准版 | [app-standard-release.apk](https://github.com/lichong152/starriver/releases/latest/download/app-standard-release.apk) | 默认选它，声明 HOME |
| 守护版 | [app-guard-release.apk](https://github.com/lichong152/starriver/releases/latest/download/app-guard-release.apk) | 固件拒绝声明 HOME 的 APK 时用 |

## 许可

MIT。参考项目的许可与版权归各自作者所有。
