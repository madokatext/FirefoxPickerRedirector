# Firefox Picker Redirector

面向 Firefox for Android 153.x 的 LSPosed/Xposed 模块。

## 作用

- 仅 Hook `org.mozilla.firefox`。
- Firefox 网页 `<input type=file>` 的普通文件选择入口仍保留 Firefox 原本的 MIME、多选等参数，但把目标包限定为你指定的文件管理器。
- 纯图片/视频上传不再定向到第三方文件管理器：Firefox 原本的“文件选择 + 相机/录像” chooser 会被整体替换为系统 `MediaStore.ACTION_PICK_IMAGES` 媒体选择器。
- 因此照片/视频入口不会再出现“相机 vs 第三方文件管理器”二选一；相机/录像 capture 入口会从这类纯媒体上传中消失。普通文件上传仍定向到配置的文件管理器。
- 目标文件管理器不存在或不能处理相应 Intent 时，自动保留 Firefox 原行为，避免直接崩溃。

默认目标是 Material Files：`me.zhanghai.android.files`。

## Firefox 153.0.2 源码依据

Firefox 153.0.2 是 Android-only 点版本。已对照 153.0 release 与 153.0.2 已知 uplift revision；本模块依赖的 `FilePicker.kt` 与 `MimeType.kt` 在该区间内容未变化。

对应 Android Components `FilePicker`：

- 普通文件上传由 `MimeType.Wildcard.buildIntent()` 构造 `ACTION_GET_CONTENT`，带 `CATEGORY_OPENABLE`、`EXTRA_LOCAL_ONLY`、`EXTRA_MIME_TYPES` 和 `EXTRA_ALLOW_MULTIPLE`。
- 多个文件/相机/录像/录音入口通过 `Intent.createChooser()` + `EXTRA_INITIAL_INTENTS` 合并。
- 权限被拒绝且请求是图片/视频时，走 AndroidX `PickVisualMedia` / `PickMultipleVisualMedia`。
- 文件选择请求码为 `7113`。

Material Files 的 `FileListActivity` 同时声明支持 `ACTION_GET_CONTENT` 与 `ACTION_OPEN_DOCUMENT`，`*/*` MIME，因此可以直接作为定向目标。

## 构建

项目使用 AGP 8.7.3、compileSdk 35、Java 17。

`app/libs/xposed-api-stub.jar` 只是最小编译桩，并通过 `compileOnly` 引用，不会被打进 APK；运行时由 LSPosed/Xposed 提供真实 API。

在 Android Studio 打开项目并构建 `app` 即可。如果你的环境使用别的 AGP，可直接调整根目录 `build.gradle` 的插件版本。

## 使用

1. 安装模块 APK。
2. 打开模块，填入目标文件管理器 package name；默认已填 Material Files。
3. 在 LSPosed 中启用模块，作用域只选 Firefox。
4. 强制停止并重新打开 Firefox（首次启用模块时需要）。

后续只修改目标包名时不需要重启 Firefox；每次拉起文件选择器前都会重新读取模块设置。

## LSPosed compatibility note

The bundled compile-only Xposed API stub intentionally matches the legacy Xposed API 82 method descriptor for `XposedHelpers.findAndHookMethod`: its return type is `XC_MethodHook.Unhook`. Do not change it to `Object`; JVM method descriptors include the return type, and doing so causes `NoSuchMethodError` on LSPosed before hooks are installed.
