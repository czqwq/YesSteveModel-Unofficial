# TODO

- 控制器文件去 JSON 往返：OpenYSM 通道的 `createControllerJson` → `OpenYsmAnimationControllerRegistry` 解析仍在往返（每包几 KB，收益低）；直构 `OpenYsmControllerDefinitions` 需另一轮对拍。
- Rust 原生层：`YSMBinarySerializer.convertRgbaToPng` / `RawYsmModelAdapter.convertRgbaToPng` 的 RGBA→PNG 编码仍走 Java 循环 + `ImageIO.write`；实测该步 Rust 快 7×（1024² 约省 0.3 s/纹理），接入时沿用 `ModelImageConverter.encodePngNative` 的 sRGB 守卫与 fallback。
- Rust 原生层：预分发目前只有 `windows-x86_64`（`native/` + `-PysmuNative=build` 机制已就绪）；`linux-x86_64`/`linux-aarch64`/`macos-x86_64`/`macos-aarch64` 仍需各自构建后放进 `native/<platform>/`，否则那些平台一直走 Java 回退。
- Rust 原生层：legacy `.ysm` v3 容器导入（chacha + dirty zstd + 旧格式转换，对应上游 native `modules/legacy/**`）尚未移植，`.ysm` 首次导入仍是 Java。
- Rust 原生层：`ClientModelManager` 的 GUI 图片解码（`guiImageTextureFor` 里的 `ImageIO.read`）可改走 `OuterFileTexture.decode` 的 native PNG 路径；数量少、收益低。
