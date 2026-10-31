# DailyOne

Android 日记应用，Kotlin + Jetpack Compose。离线写作，按时间浏览图文日记，使用自己的 WebDAV 存储同步。

## 当前实现

- 按月分组的圆角日记卡片、日期胶囊、图文摘要、多图缩略图和剩余数量。
- 独立阅读/编辑状态、逐次自动保存、多选图片导入、图片移除和排序、删除确认。
- 按横竖比例组合的图片排版、全屏左右翻页、双击/捏合缩放查看。
- SQLite 本地持久化；图片复制到应用私有目录，离线可用。
- HTTPS WebDAV 手动双向同步，支持上传、下载、删除标记和冲突副本。
- WebDAV 配置使用 Android Keystore 的 AES-GCM 密钥加密存储。
- 浅色与深色界面、系统返回键、键盘避让。

列表、阅读及图片排版参考已提供的 Day One 安卓截图，保留已确认的核心功能。界面不添加未实现的日历、地图等导航。

## 开发与构建

需要 JDK 17、Android SDK Platform 35 和 Build Tools 34.0.0。Android Studio 可以直接打开此目录。

```sh
# 设置 JAVA_HOME 和 ANDROID_HOME，或在 local.properties 中配置 sdk.dir
./gradlew testDebugUnitTest lintDebug assembleDebug
```

输出 APK：`app/build/outputs/apk/debug/app-debug.apk`。最低支持 Android 8.0（API 26）。

仓库包含 Gradle Wrapper（8.9，校验 SHA-256）及 GitHub Actions 构建流程，CI 构建产物名为 `dailyone-debug`。

## WebDAV 使用

1. 在支持 WebDAV 的服务上准备一个已有目录，使用 HTTPS 地址和应用专用密码。
2. 在应用右上角打开云存储设置，填写地址、用户名和密码并保存。
3. 返回列表点击“立即同步”。其他安卓设备使用相同配置即可拉取日记。

应用在所填目录下创建自己的 `DailyOne/entries/` 和 `DailyOne/photos/` 目录。记录为带版本号的 JSON，图片为独立 JPEG；不是 Joplin 同步格式。服务需要支持 MKCOL、PROPFIND、GET、PUT、强 ETag 及 If-Match / If-None-Match 条件写入。跳转地址不会自动跟随，请填写服务最终提供的 WebDAV 地址。

同步不依赖设备时间判断覆盖：只有服务器版本未变化才会写入。两端同时修改时，保留本地冲突副本，再接收云端版本；412 响应会停止本轮同步，保留本地内容，下次同步继续处理。删除通过记录中的标记传播，不物理清除云端文件或图片。请勿手动删除或编辑同步目录中的文件。

当前只在用户点击时同步，没有后台同步。同步期间列表暂不可编辑。网络失败可重试；同步中途完成的记录会保留。

## 数据与边界

- 日记未实现端到端加密；云存储服务可以读取同步内容。HTTPS 提供传输加密，凭据在手机上加密存储。
- 图片导入会缩放并转为 JPEG（最长边约 2400px），不保留原始文件。每篇最多 20 张。
- 卸载应用会删除本机数据；使用前请先完成云同步。系统自动备份关闭，避免把凭据密文和设备密钥分开恢复。
- 目前没有本地导出、搜索、日历、多日记本、提醒、订阅或账号后台。
- 单元测试覆盖文档格式、路径校验、版本冲突判定、WebDAV 条件写入及 XML 安全解析；真实网盘和安卓设备仍需实测。

## 设计原型

`design/prototype.html` 是可交互的原型源文件，`design/index.html` 可用浏览器打开。原型数据不属于安卓应用数据。
