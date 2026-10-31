# TODO

- ZIP 模型解析：读取 config/ysmu/{custom,auth} 下的 .zip 模型包（管理界面同时列出 ZIP 类型）。
- 控制器文件去 JSON 往返：OpenYSM 通道的 `createControllerJson` → `OpenYsmAnimationControllerRegistry` 解析仍在往返（每包几 KB，收益低）；直构 `OpenYsmControllerDefinitions` 需另一轮对拍。
