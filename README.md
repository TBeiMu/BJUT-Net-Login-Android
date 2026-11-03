# 工小助

面向北京工业大学校园网的 Android 登录助手。

计划实现：连接校园 WiFi 后，打开 App 即可使用已保存的默认账号登录，无需反复填写账号密码。支持学校公共 WiFi 和宿舍光猫 WiFi，提供多个账号的本地加密管理。

> 第二阶段已完成：安卓工程、首页、账号管理、设置和本地加密存储已实现，已在 Android 12 模拟器验证。当前阶段版不会发送认证请求；校园网自动登录将在第三阶段接入，真实校园接口尚未验证。

## 第一版功能

- 支持 Android 12 及以上版本，采用 Kotlin 和 Jetpack Compose。
- 添加、编辑、删除多个账号，设置默认登录账号。
- 使用 Android Keystore 管理密钥，以 AES-GCM 加密保存账号资料；账号数据排除备份。
- 打开 App 时自动登录，可关闭自动登录，也可手动登录或重试。
- 支持公共 WiFi 的 wlgn 认证和宿舍 WiFi 的两阶段 IPv4 认证。
- 显示网络类型、默认账号、认证状态和失败原因。
- 登录请求明确通过当前 WiFi 发送，避免未认证时误用移动数据。

第一版不包含后台常驻、定时保活、IPv6 认证、运营商选择、云端同步或自动连接 WiFi。

## 开发阶段

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 1 | 创建 GitHub 仓库、本地 Git 关联、README 和忽略配置 | 已建立 |
| 2 | 创建安卓工程，完成界面与加密账号管理 | 已完成（模拟器验证） |
| 3 | 实现公共 WiFi、宿舍 WiFi 识别与自动登录 | 待开发 |
| 4 | 自动化测试、构建检查和校园实机联调 | 待验证 |
| 5 | GitHub Actions、版本标签和 Releases APK 发布 | 待配置 |

## 手机下载与更新

后续稳定版本将在 [GitHub Releases](https://github.com/TBeiMu/BJUT-Net-Login-Android/releases) 提供 APK、安装说明和更新日志。目前尚未公开发布 APK；第二阶段可按下方步骤本地构建预览安装包。

发布版本将保持相同签名密钥，并递增 Android `versionCode`，支持覆盖安装。历史版本标签和 APK 将保留，方便追溯问题。

源码回滚采用 `git revert`，保留提交历史。需要向已安装用户回退功能时，将发布更高版本号的修复版本，避免安卓拒绝降级安装。

## 第二阶段已实现

- 首页显示默认账号、已保存的网络模式和自动登录偏好，明确提示登录尚未接入。
- 账号页支持添加、修改、删除和设为默认；首个账号自动成为默认账号，删除默认账号后选择剩余的第一个账号。
- 备注可选，同一校园网账号不重复保存；账号首尾空格会去除，密码内容原样保留。
- 密码输入默认隐藏，编辑窗口禁止截图；账号列表不显示密码。
- 设置页支持保存自动登录开关和自动识别／公共 WiFi／宿舍 WiFi 模式，界面适配系统深色模式。
- 整份账号资料及设置通过 Android Keystore 与 AES-256-GCM 加密，使用随机 IV，原子写入 `noBackupFilesDir`，并禁用备份与设备迁移。
- 文件损坏或密钥丢失时，阻止覆盖保存，保留文件并提供重试和明确确认后的清除入口。

当前调试版本为 `0.1.0-stage2`，包名为 `io.github.tbeimu.gongxiaozhu.debug`。调试版本与未来正式版本使用独立的包名和数据目录，暂不提供账号迁移。

## 本地构建

需要 JDK 17 或 21、Android SDK Platform 36 和 Build Tools 36.0.0。已提交 Gradle Wrapper，无需单独安装 Gradle。

在项目根目录的 `local.properties` 配置 SDK 路径，例如 Windows：

```properties
sdk.dir=C\:/Users/<你的用户名>/AppData/Local/Android/Sdk
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS／Linux：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

安装包输出为 `app/build/outputs/apk/debug/app-debug.apk`，可复制到 Android 12 及以上手机安装。此阶段仅用于预览界面与账号管理，建议使用虚构账号测试。

连接安卓设备或启动模拟器后，可执行安卓端测试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

构建依赖采用已验证的固定版本组合。Lint 将其他警告视为错误；版本升级提示单独评估，不作为功能质量检查失败项。

## 第二阶段验证结果

- 18 个 JVM 单元测试通过：账号增删改、默认账号切换、设置保存、特殊字符密码、不同 IV、明文检查、篡改／密钥丢失、写入失败保护和恢复流程，以及 App 打开期间文件损坏／密钥丢失时的写入保护。
- 5 个 Android 12（API 31）模拟器测试通过：真实 Android Keystore 与 AtomicFile、密钥丢失／损坏文件保护、界面增删改入口、默认账号和设置保存、Activity 重建。
- Lint 通过，无问题；Debug APK 构建、模拟器安装及冷启动通过，首页已视觉检查。
- 测试只使用虚构凭据；尚未验证真实手机、真实账号、校园 WiFi 认证或正式 APK 覆盖更新。

下一阶段：接入当前 WiFi 的网络访问、公共 WiFi 登录和宿舍两阶段 IPv4 认证。

## 验证目标

- 账号保存后，重启 App 仍可读取；增删改及默认账号切换正常。
- 密码中的特殊字符正确编码，日志不记录密码或完整登录 URL。
- 公共 WiFi 和宿舍两阶段认证正确处理成功、已登录、密码错误、超时和异常响应。
- 无 WiFi、普通 WiFi、移动数据同时开启、网络切换和重复打开 App 时行为明确。
- 校园实机验证：保存一次账号后，再次打开 App 无需填写账号密码即可完成认证。
- 发布后验证手机 APK 下载、安装及覆盖更新。

## 凭据与网络

真实账号密码、签名密钥及其口令不得提交到仓库。发布签名所需机密将通过 GitHub Actions Secrets 配置；本机 SDK 路径、缓存和构建产物由 `.gitignore` 排除。

登录凭据仅发往已知校园认证地址，携带凭据的请求禁止自动跳转到其他地址。学校参考协议使用 HTTP，本地加密只保护手机中的账号存储，不改变网络传输方式。

## 协议参考

认证流程参考 [TwiceTry/BJUT_Net_Login](https://github.com/TwiceTry/BJUT_Net_Login) 中的公共 WiFi 与宿舍 WiFi 协议。安卓实现将独立编写，不在手机运行 Linux Shell 脚本。

参考项目最后更新于 2023 年；实际接口和参数须在校园网内实测确认。当前尚未验证任何真实账号或校园认证服务。
