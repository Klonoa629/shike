<p align="center">
  <img src="docs/branding/shike-icon.svg" width="96" alt="时课图标">
</p>

<h1 align="center">时课 · Shike</h1>
<p align="center">按真实时间安排课程，让每一次调课都独立。</p>
<p align="center">Android 8.0+ · Kotlin · Jetpack Compose · MIT</p>

[![Android CI](https://github.com/Klonoa629/shike/actions/workflows/android.yml/badge.svg)](https://github.com/Klonoa629/shike/actions/workflows/android.yml)

时课是一款适配安徽理工大学教务系统的 Android 课程表，支持教务导入和手动安排实验课、课程设计。采用实际时间轴，课程不必拘泥于固定节次。

> 个人开源项目，非学校官方应用。首次使用需要有效的安徽理工大学教务账号。

## 特性

- **时间轴周视图**：左右滑动切周，点击卡片查看详情，长按编辑当次课程。
- **灵活排课**：多周批量添加、多组时间与教室安排；调整一次课程不影响其他周。
- **教务同步**：整学期导入、手动与到期自动同步，调课冲突确认后合并，失败保留原有数据。
- **离线与历史学期**：已保存课表可离线查看，旧学期独立保留。
- **本地备份**：导出课程与个人修改，恢复前预览并确认。
- **简洁外观**：白天、黑夜、跟随系统三种模式。

## 开始使用

[下载正式版](https://github.com/Klonoa629/shike/releases/latest)

登录后同步当前学期；点击“＋”补充线下课程，在设置中切换学期、外观或备份课表。

课程须安排在 **08:00—22:00**，每次不少于一小时，且不能重叠。历史学期只读。验证码登录暂未支持，后台同步可能受系统省电限制。

账号凭据通过 Android Keystore 加密保存在本机，课程直接从学校获取，无需自建服务器。导出的 JSON 备份不含密码，但包含可读课程和备注，请妥善保管。

## 构建

使用 Android Studio 打开项目，准备 **Android SDK 37、Build-Tools 36.0.0** 和兼容的 JDK。本项目已在 JBR 25 下验证，使用 Gradle Wrapper 构建：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

需先配置 `JAVA_HOME` 和 `ANDROID_HOME`；Linux / macOS 可用 `bash ./gradlew` 替代，尚未做完整平台验证。调试 APK 输出至 `app/build/outputs/apk/debug/`。

技术栈：Kotlin · Jetpack Compose · Room · WorkManager。1.0.1 已通过 Debug / Release 各 55 项单元测试及 26 项真机数据库测试，主要验证机型为 Redmi K90。

提交和 PR 自动运行构建、单元测试与 Lint；在 [Actions](https://github.com/Klonoa629/shike/actions) 下载调试 APK 和检查报告。正式版仍从 Releases 下载。

[构建与签名](docs/BUILDING.md) · [更新记录](CHANGELOG.md)

## 许可证

本项目采用 [MIT License](LICENSE)。Copyright © 2026 [Microft Chou](https://github.com/Klonoa629)。
