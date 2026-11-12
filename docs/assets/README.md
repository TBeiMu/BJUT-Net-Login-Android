# 小鸟 Logo 素材

`logo-bird-source.png` 是用户提供图片的无损重编码归档，像素保持一致，不保存原文件位置或外部元数据。`logo-bird-foreground.png` 为桌面图标准备前景，保留白色脸部和身体，去掉图标外部浅色背景并适配 Android 图标裁切。

原始附件 SHA-256：`183c368fef82e926da473654c61e5f3db4d14d9937604da1ef1367388750b1bd`；归档文件 SHA-256：`905939baee183f0163d8e812d9e35fa5fcf5344b110a6b99e581e94803db6c77`。原始图片为 1254 × 1254 像素。

在 Python 3.12、Pillow 12.0.0、NumPy 2.2.6 环境运行：

```text
python scripts/prepare-bird-logo.py
```

生成桌面彩色前景、关于页图像、主题单色图标及通知单色图标。只使用附件像素及其轮廓，不生成新角色。开屏和应用内加载动画按用户要求继续使用原来的“工”字标志，脚本不会更改这些资源。上述工具仅用于素材生成，不增加 Android 依赖或运行权限。
