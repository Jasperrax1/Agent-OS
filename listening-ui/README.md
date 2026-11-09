# 语音聆听动效工作区

在 Mac 浏览器直接打开 `preview/index.html`。用「播放完整流程」看静音、出字、修正、识别确认和生成的连续过渡，也可以单独查看任一状态、调模拟音量或换成本地图片和视频。预览只在浏览器里播放本地模拟数据。

- `runtime/listening-ui.css` 是样式与动效。文件顶部的变量集中控制胶囊、流光、模糊和状态过渡。
- `runtime/listening-ui.js` 渲染状态和实时转写。输入为 `phase`、`text`、`level`，音量范围为 0–1。
- `preview/` 包含 Mac 专用页面、模拟状态和控制项。调测试句或播放时间轴时只改这里。

预览和 Android 宿主共用同一对 runtime 文件。浏览器传入一个铺满 4:3 画面的定位容器，另传 DOM 背景用于渐进模糊。Android 在 CameraX 预览上合成等参数的模糊。

```js
const ui = window.ListeningUI.mount(host, { backdrop: scene, onStop: finishListening });
ui.update({ phase: "listening", text: transcript, level: speechLevel });
ui.update({ phase: "checking", text: finalTranscript, level: 0 });
ui.update({ phase: "generating", level: 0 });
ui.destroy();
```

状态支持 `ready`、`entering`、`listening`、`checking`、`generating` 和 `complete`。宿主掌握业务时序，runtime 只渲染传入的状态。

在工作区运行 `./check-runtime.sh` 可检查正式目录的文件白名单和常见模拟、占位及调试标记。检查只读。从 V2 根目录运行 `./sync-listening-ui.sh` 同步正式资源，或运行 `./build-and-install.sh [设备序列号]` 同步、构建和覆盖安装 Debug APK。

## 《动效参考.mov》对照

- 0–0.10 秒，底部暖白光横向展开。约 0.10–0.53 秒，蓝紫光雾从底部上掠，沿边缘出现冷暖高光。约 1.1 秒后，边缘基本退场。
- 聆听阶段，底部光从蓝青到薄荷绿、暖白，主要做亮度和体积的呼吸，亮心只小幅横移。预览的音量响应设为约 80 毫秒起、220 毫秒收。视频混有配音和音效，无法从它反推真实音量映射。
- 胶囊采用浅色磨砂底、动态宽度。新增和改写的文字轻微上浮、由虚到实，已确认的字保持稳定。
- 视频在实时转写中结束。预览的生成段沿用相同色系，扩大到全画面模糊，以中间移动光斑和绕边亮线呈现，完整流程中约 2.2 秒。

## 逐段验收（图 1–7）

「入场」会单独播放到聆听提示。时间按 1× 计算，0–0.54 秒为底部扩光，约 0.23–1.12 秒为宽幅柔扫，1.08 秒起点亮渐变描边，约 1.28–2.04 秒为两侧同步下行，2.06 秒开始升起「我在听，请说」。提示与转写分别处理。

「聆听」可调音量验证底部彩光和向外扩散的软涟漪。背景浅、深两级采样通过连续透明度坡道混合，从画面中线以下逐渐加深。胶囊用半透明底色和柔光表现玻璃感。「生成」可单看五色光雾，背景模糊在 512×384 画幅下约 38 像素，完整回放保留约 2.2 秒。生成是按图 7 补做的阶段。浏览器启用减少动态效果时，保留静态光色和提示。

预览可在控制台执行 `window.__maTimeline.seek(1.54)` 冻结到指定秒数，再用页面按钮恢复正常播放。
