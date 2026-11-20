# Gloom-cn

基于 [MateriiApps/Gloom](https://github.com/MateriiApps/Gloom) 的定制版本。

> ⚠️ **声明 / Disclaimer**
>
> 本仓库的全部代码修改均由 AI（Alma）自动完成，**未经人工审查**。
>
> All code changes in this repository were made entirely by AI (Alma) **without human review**.

## 构建环境 / Build

- JDK 21（Java 25 与 Gradle 8.11.1 不兼容）
- Android SDK（compileSdk 35）
- 项目路径包含中文，已在 `gradle.properties` 启用 `android.overridePathCheck=true`
- Release 构建未启用 R8 混淆（`isMinifyEnabled = false`）

## 相关链接 / Links

- 上游项目：https://github.com/MateriiApps/Gloom

## 致谢 / Acknowledgements

本项目的应用内翻译功能参考并移植了以下优秀开源项目的实现思路，在此一并致谢：

- [FluentRead](https://github.com/FluentRead/FluentRead) —— 免费翻译服务的分流逻辑（加权分流、失败冷却、连接检测）与多家免费翻译服务接口的参考来源，本项目翻译服务的核心灵感与实现蓝本
- [MateriiApps/Gloom](https://github.com/MateriiApps/Gloom) —— 本项目的上游，感谢其提供的优秀 GitHub 客户端基础

