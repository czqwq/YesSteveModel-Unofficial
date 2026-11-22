# 预分发原生库

每个平台一个目录，目录名与 `NativeLoader.platformTag()` 一致（`windows-x86_64`、`linux-aarch64`、`macos-x86_64` …）。
`processResources` 会把这里的内容打进 jar 的 `assets/ysmu/native/<platform>/`，所以发布 jar 不需要 Rust；
缺失的平台由 `NativeLoader` 自动回退 Java。

- 更新本平台：`.\gradlew.bat -PysmuNative=build processResources`（要求 cargo，产物覆盖本平台文件）。
- 新增平台：把编好的 `ysmu_native.dll` / `libysmu_native.so` / `libysmu_native.dylib` 放进 `native/<platform>/`。
- 修改 Rust 代码后记得重编对应平台并更新这里，否则 jar 里还是旧库。
