# ExtraordinaryDynamicBackup

Minecraft 26.3 的 Fabric Java 空项目，Java 包与 Maven group 为 `org.edtp`。

## 在 IntelliJ IDEA 中打开

1. 选择 Open，打开本项目目录，按 Gradle 项目导入。
2. Project SDK 和 Gradle JVM 选择 Microsoft JDK 25：`C:\Users\think book\.jdks\ms-25.0.2`。
3. 等待 Gradle 同步完成，使用生成的 Minecraft Client / Minecraft Server 运行配置。

`gradle.properties` 已配置本机 JDK 路径；移动到其他电脑时需要修改 `org.gradle.java.home`。

## 命令

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
```

构建产物：`build/libs/ExtraordinaryDynamicBackup-0.1.0.jar`。
入口类位于 `src/main/java/org/edtp` 和 `src/client/java/org/edtp`。
项目仅包含空初始化入口，没有备份功能或示例游戏内容。
