# 工小助

北京工业大学校园网 Android 登录助手。连接校园 WiFi 后打开 App，使用已保存的默认账号登录，减少反复输入账号密码。

**支持 Android 12 及以上 · 当前版本 v0.1.1**

## 下载与安装

[下载正式版 APK](https://github.com/TBeiMu/BJUT-Net-Login-Android/releases/latest/download/gongxiaozhu.apk) · [所有版本与更新说明](https://github.com/TBeiMu/BJUT-Net-Login-Android/releases) · [SHA-256 校验文件](https://github.com/TBeiMu/BJUT-Net-Login-Android/releases/latest/download/SHA256SUMS.txt)

下载 `gongxiaozhu.apk` 后，在手机上允许下载应用的浏览器或文件管理器“安装未知应用”，完成安装。

正式包与此前调试包独立安装，首次使用需要重新添加账号。之后的正式版保持相同签名，可直接覆盖安装并保留账号；请勿先卸载。

> 验证状态：自动化测试、Lint、构建及 Android 12 模拟器检查通过，已有用户初步手机反馈。公共／宿舍校园网的完整实机验收仍待完成，后续实测结果会更新在此处。正式发布不代表所有校园场景均已验证。

## 功能

- 管理多个账号：添加、编辑、删除和设置默认账号；首个账号自动成为默认账号。
- 本机加密存储：使用 Android Keystore 和 AES-GCM 保存账号、密码及设置，密码输入默认隐藏。
- 前台自动登录：打开 App 后最多自动尝试一次，也可关闭自动登录或手动重试。
- 校园 WiFi 认证：支持公共 WiFi 和宿舍 WiFi 两阶段 IPv4 认证。
- 网络类型识别：根据已知认证服务的响应识别；有歧义时手动选择公共或宿舍 WiFi，并保存选择。
- 明确认证状态：已经认证时保持会话，不自动退出；失败后显示提示并停止自动重试。
- 使用当前 WiFi：请求绑定实体 WiFi，网络切换或 App 离开前台时取消，不改走移动数据。

## 使用方法

1. 在系统设置中连接北工大校园 WiFi。
2. 打开工小助，在“账号”中保存校园网账号和密码，确认默认账号。
3. 回到首页，点击“登录 / 重试”；之后重新打开 App 时，自动登录会使用默认账号。
4. 若提示无法确定网络类型，在首页或“设置”中选择“公共 WiFi”或“宿舍 WiFi”，再手动登录。
5. 查看首页结果，并用浏览器打开外部网站确认可以上网。

修改默认账号用于后续登录，不会退出现有网络会话。认证失败后，检查账号、密码和网络类型，再手动重试。

## 账号与隐私

账号资料加密保存在手机本机，不做云端同步；登录时仅向固定校园认证地址提交选定的默认账号。账号数据排除系统备份和设备迁移，卸载应用或清除数据后需要重新录入。

学校参考认证协议使用 HTTP。本机加密保护手机中的账号存储，不代表认证请求的网络传输已加密。请求禁止跳转到其他地址，日志和错误提示不显示密码或完整认证 URL。

如果账号文件损坏或密钥不可用，App 会提示重试读取；明确确认清除后，才能重新录入，不会静默覆盖原数据。

## 常见问题

**没有 WiFi 或连接的是普通 WiFi？**

App 不负责自动连接 WiFi。先在系统设置中连接校园 WiFi；未识别到校园服务时，不会轮流提交已保存的账号。

**宿舍 WiFi 只完成第一阶段？**

宿舍网络还需要 IPv4 二次认证。查看首页的具体提示，确认账号和网络类型，必要时使用学校登录页面检查；已有认证不会被 App 主动退出。

**反馈问题需要提供什么？**

记录 App 版本、手机型号、Android 版本、公共／宿舍 WiFi 类型、首页提示和实际能否上网即可。请勿在 Issue 或截图中提供真实密码或完整认证请求 URL。

## 开发

使用 Kotlin、Jetpack Compose 和协程。需要 JDK 17 或 21、Android SDK Platform 36 和 Build Tools 36.0.0。

在被忽略的 `local.properties` 中配置 SDK，例如：

```properties
sdk.dir=D\:/Applications/Development/Android/Sdk
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS／Linux 使用 `./gradlew`。连接独立测试模拟器后，可运行 `:app:connectedDebugAndroidTest`；界面测试会清除调试版账号，请勿在存有真实账号的设备上执行。

GitHub Actions 自动执行测试、Lint 和构建，版本标签触发正式签名与 Releases 发布。[发布与签名说明](docs/发布.md)介绍固定签名、覆盖更新和回滚约定。

当前不包含后台常驻、定时保活、IPv6 认证、运营商选择、云端同步或自动连接 WiFi。

## 协议参考

认证流程参考 [TwiceTry/BJUT_Net_Login](https://github.com/TwiceTry/BJUT_Net_Login)。安卓逻辑独立实现，不在手机上运行 Linux Shell 脚本。参考项目较旧，校园接口可能变化，需要根据实测持续维护。
