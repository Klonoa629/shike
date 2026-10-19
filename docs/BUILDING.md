# 构建与签名

## 环境

- Android SDK Platform 37、Build-Tools 36.0.0。
- Gradle Wrapper 9.6.0、AGP 9.4.0，版本已固定在仓库中。
- 已验证 JBR 25；JVM 字节码目标为 17。
- Windows 辅助脚本需要 PowerShell 7，SDK 和 JDK 路径按实际安装位置传入。

## 调试构建

在项目根目录执行：

```powershell
pwsh -NoProfile -File ./scripts/build.ps1 -Online `
  -SdkPath 'C:\Android\Sdk' `
  -JavaPath 'C:\Program Files\Android\Android Studio\jbr'
```

依赖缓存齐全后移除 `-Online` 可离线构建。脚本执行 APK 构建、JVM 单元测试和 Lint；添加 `-DeviceTests` 额外生成真机测试 APK，不自动安装或运行。

```powershell
adb install -r ./app/build/outputs/apk/debug/app-debug.apk
adb install -r ./app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cn.aust.mytimetable.debug.test/androidx.test.runner.AndroidJUnitRunner
```

真机测试使用独立数据库。测试报告在 `app/build/reports/`，Room schema 位于 `app/schemas/`。接口测试样本已脱敏，不含登录凭据。

## 正式构建

首次创建自己的签名并构建：

```powershell
pwsh -NoProfile -File ./scripts/build-release.ps1 -InitializeSigning -Online `
  -SdkPath 'C:\Android\Sdk' `
  -JavaPath 'C:\Program Files\Android\Android Studio\jbr'
```

已有签名后移除 `-InitializeSigning`。脚本执行 Release 构建、Release 单元测试、Lint 和 APK 签名验证，输出 `app/build/outputs/apk/release/app-release.apk`。

签名密钥位于 `.signing/`，密码使用 Windows DPAPI 保护，只在构建进程中解密使用。此辅助脚本仅适用于 Windows；其他平台需要自行配置 `SHIKE_KEYSTORE_FILE` 和 `SHIKE_STORE_PASSWORD` 环境变量，密钥别名为 `shike`。

签名文件不要提交源码或上传 Release。密码保护依赖当前 Windows 用户，应将密钥和可恢复的密码分别备份；以后更新必须继续使用同一签名。

## 安装与迁移

| 变体 | 应用名 | 包名 |
| --- | --- | --- |
| Release | 时课 | `cn.aust.mytimetable` |
| Debug | 时课（测试版） | `cn.aust.mytimetable.debug` |

两版可并存，数据独立。从测试版迁移时，先导出最新备份，再在正式版使用同一账号登录并恢复。核对课程和备注后再决定是否移除旧版。不同签名无法直接覆盖升级，请勿通过卸载丢弃尚未备份的数据。
