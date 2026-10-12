# 多文件夹相机 (MultiFolderCamera)

一个 Android 拍照应用：**在桌面生成多个独立图标，从不同图标点进去拍的照片，自动保存到不同的文件夹。**

## 工作原理

- 每个「拍照入口」在 Room 数据库中保存一条记录（名称 / 顶级目录 / 图标颜色）。
- 点「加到桌面」时，通过 **Pinned Shortcut**（`ShortcutManager.requestPinShortcut`）把该入口钉到桌面，
  每个快捷方式的 `Intent` 携带 `folder_id`。
- 点击桌面图标启动 `CameraActivity`，读取 `folder_id` 后，用 **CameraX** 取景拍照，
  再通过 **MediaStore** 把照片写入 `Pictures/<rootDir>/<name>/`，系统相册会按子目录归类。

## 功能

- 任意数量拍照入口，各自对应独立桌面图标
- 拍照即按入口归类，无需事后整理
- 图标按入口名称首字母 + 颜色自动区分
- 分区存储（Scoped Storage）适配，Android 8.0+ 可用

## 目录结构

```
MultiFolderCamera/
├── .github/workflows/build.yml   # GitHub Actions 自动编译
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/.../
│       │   ├── MainActivity.kt        # 入口管理页
│       │   ├── CameraActivity.kt      # 拍照页（按 folderId 存图）
│       │   ├── data/                  # Room：FolderEntry / Dao / DB / Repository
│       │   └── util/                  # ShortcutHelper / MediaStoreHelper
│       └── res/                       # 布局与资源
├── gradle/wrapper/                  # Gradle Wrapper 配置
├── build.gradle / settings.gradle
└── gradlew / gradlew.bat
```

## 本地编译前准备（重要）

仓库已包含 `gradle-wrapper.properties` 与 `gradlew` / `gradlew.bat` 脚本，
但**还差一个二进制文件 `gradle/wrapper/gradle-wrapper.jar`**，需要你在本地生成后一并提交，
否则 CI 里的 `./gradlew` 无法运行。两种方式任选其一：

1. 本地已安装 Gradle：在项目根目录执行
   ```bash
   gradle wrapper --gradle-version 8.9
   ```
2. 用 Android Studio 打开本项目，它会自动补全 Gradle Wrapper（包括 jar）。

然后把整个目录提交到 git：
```bash
git add .
git commit -m "init multi-folder camera"
git push
```

## 用 GitHub Actions 自动编译

推送代码（或发起 PR、或手动 `workflow_dispatch`）后，Actions 会自动：
1. 安装 JDK 17 与 Android SDK
2. 接受 SDK 许可
3. 执行 `./gradlew assembleDebug`
4. 在 Artifacts 中提供 `app-debug.apk` 下载

> 首次运行会自动下载 Android SDK 平台（compileSdk 34）与构建工具，请确保 Actions 运行环境可联网。

## 使用步骤

1. 安装并打开 App，点右下角 `+` 新建入口（如「家人」「票据」）。
2. 在列表中点该入口的「加到桌面」，按提示把图标钉到桌面。
3. 之后直接点桌面图标即可拍照，照片自动进入对应文件夹。

## 技术栈

- Kotlin + MVVM
- CameraX（相机）
- Room（入口配置持久化）
- MediaStore（分区存储写入）
- Pinned Shortcut（桌面多图标）
- AGP 8.5.2 / Gradle 8.9 / compileSdk 34
