# 给代理的安装指令（复制这段发给替你装电视的人或 AI）

> 用法：把 `<电视IP>` 换成电视的局域网地址，整段发出去。执行手册在 [AGENTS.md](AGENTS.md)。

---

请帮我把「星川 / Star River TV」装到我的电视上，并设置为默认桌面；如果主页键被固件抢走，再启用桌面守护。

电视 IP：`<电视IP>`
保持 ADB 常开：要（注意：星川本版**没有**这个开关，第 7 节说明了替代做法）
安装说明：
https://raw.githubusercontent.com/<你的账号>/<你的仓库>/main/AGENTS.md

请先完整阅读安装说明，再在我的电脑上亲自运行命令，不要只把步骤告诉我。
需要我在电视上操作时请提醒我（比如 ADB 授权弹窗、首次按主页键选「始终」、无障碍设置里勾选）。

不要未经确认重启、待机、关机、卸载旧版、清空配置或停用原厂桌面。
签名不匹配时先寻找原签名密钥，不要自动卸载。
请先记录默认 HOME、屏保、无障碍服务和通知监听，安装后分别验证安装、主页键、待机唤醒和开机。
没有实际测过的项目不要写成通过。

最后把下面命令的原始输出贴给我，每条 adb 都加 `-s <电视IP>:5555`：

- `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
- `adb shell settings get secure enabled_accessibility_services`
- 按主页键并等待两秒后：`adb shell "dumpsys window | grep mCurrentFocus"`

---

## 附：这条指令对应星川的哪几件事

| 指令里的要求 | 星川的对应做法 |
|---|---|
| 设为默认桌面 | `cmd package set-home-activity app.xingchuan.tv/.MainActivity`（standard 形态） |
| 桌面守护 | 无障碍服务 `app.xingchuan.tv/app.xingchuan.tv.core.GuardService`，设置面板「守护·关 → 守护·开」 |
| 固件拒装 HOME 声明 | 换 `app-guard-release.apk`（不声明 HOME，装成普通应用） |
| 保持 ADB 常开 | **未实现**。小米/红米每次唤醒会关 ADB，只能手动重开 |
| 屏保 / 通知监听 | 星川不注册、不修改，记录只为回滚时能证明与星川无关 |
