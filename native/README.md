# 预分发原生库

每个平台一个目录（目录名与 `NativeLoader.platformTag()` 一致），`processResources` 会把这里的内容打进 jar 的
`assets/ysmu/native/<platform>/`，所以发布 jar 不需要 Rust；缺失的平台由 `NativeLoader` 自动回退 Java。
多平台产物在各自平台的原生 runner 上构建（不做交叉编译），产出的是未打包的 cdylib 原文件，按上表改回规范文件名即可。

| 平台目录 | 文件名 |
| --- | --- |
| `windows-x86_64` | `ysmu_native.dll` |
| `windows-aarch64` | `ysmu_native.dll` |
| `linux-x86_64` | `libysmu_native.so` |
| `linux-aarch64` | `libysmu_native.so` |
| `macos-x86_64` | `libysmu_native.dylib` |
| `macos-aarch64` | `libysmu_native.dylib` |

- 更新本平台：`.\gradlew.bat -PysmuNative=build processResources`（要求 cargo，产物覆盖本平台文件）。
- 更新其他平台：换上对应平台的构建产物（同一份 Rust 源码构建，ABI 必须与 `NativeLoader.ABI_VERSION` 一致）。
- 修改 Rust 代码或 JNI 布局后：重编所有平台并更新这里，同时 bump 两处 `ABI_VERSION`。
