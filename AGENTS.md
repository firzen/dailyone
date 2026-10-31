# 开发约定

- 优先在本地构建和验证，GitHub Actions 用于提交后的自动检查。
- 使用 JDK 17；本机已通过 `~/.zprofile` 配置 `JAVA_HOME` 和 `ANDROID_HOME`。
- 完整验证：`./gradlew testDebugUnitTest lintDebug assembleDebug`。
- 日常仅打包：`./gradlew assembleDebug`。保留 Gradle 缓存，通常无需 `clean`。
- PHP 使用 `/Applications/XAMPP/bin/php`。
