# Muse Vision V2 横屏版

Android 私有 Debug Demo。横屏桌面、收音、生成中、Agent 控制器和视频播放器按 Figma 画板实现，并加入数字吧唧的豆包 Live 角色页。应用 ID 为 `com.musepocket.ai.vision`，版本 `0.8.1`，可以更新 V2 竖屏版并与 V1 `com.musepocket.ai` 并存。右侧圆环是装饰，桌面时间、日期和电量为参考稿的演示值。控制器及播放器展示本地 UI。

[横屏 Figma 画板](https://www.figma.com/design/prqFQ35FMMh16WT9ib6ANc/%E6%AC%A7%E7%BD%97%E5%B7%B4?node-id=85-1679)

## 使用

### 数字吧唧 Live

1. 在 Muse 桌面点左下角圆形图标，或按一次音量加。角色会在左侧圆角卡片内出现，右侧装饰环继续显示。
2. 首次进入时允许麦克风。连接后可以直接说话，角色根据收音和回答切换动画，用户转写和 AI 回复字幕都使用带流光的半透明胶囊。左右点角色可触发戳脸动画。
3. 对角色说「打开小艺慧记」「帮我把这次会议记下来」等明确指令。转写完成后打开慧记，使用内置中文模型离线转写，点击底部音波暂停或继续。首次开启会解包模型，稍等即可。点右上角关闭或返回会恢复 Live 对话。
4. 对角色说「帮我生成一个游戏」或「来个小游戏」。角色抱电脑的动画出现后约 3 秒进入预置 Flying Bird。点画面飞行，躲开障碍得分，碰撞后点画面重开。点右上角关闭或返回会恢复 Live 对话。
5. 在角色页按返回键回到桌面。离开角色页会停止豆包收音和播放，并结束 Live 会话。

慧记使用[小艺慧记 SDK 1.0.0](https://github.com/Stefan99sun/xiaoyi-huiji-sdk/tree/a6e0cbc9fc1c775de118a2c2a8a015fd12defa11)，无需额外 Key，转写保留在 SDK 面板。游戏复用 VoiceLauncher 提供的离线 Flying Bird 玩法。

### 视觉分流

1. 在桌面点右下角四宫格，或在 App 前台按音量减。Android 若把实体拍照键作为标准 `KEYCODE_CAMERA` 交给应用，拍照键也能开启视觉。
2. 首次允许相机和麦克风。原桌面先起光，收到相机画面后淡入实时预览。静音时底部光也持续缓动，麦克风在本机等待，检测到持续人声才开始腾讯云实时转写。说完停顿后自动结束，也可点「点击结束收音」提前提交。收到实时字幕后才显示转写胶囊。
3. 收音结束后，后台抓一张清晰 JPEG 并用方舟校验，实时预览继续更新。只有明确的打开指令与画面匹配，才播放至少 2 秒的生成动画，再切到对应页面。空语音自动恢复聆听，真实错误显示在底部操作行。
4. 打开控制器或播放器时释放相机。其他说法进入普通对话。问答保持实时预览，收起转写胶囊并直接播报答案，播报后恢复收音。视觉模式内最多保留最近四轮文字用于追问，回桌面后清空。系统返回手势或音量减可从视觉页回桌面。
5. Debug 版可长按底部操作行打开文字调试。可用 [AI 额度演示](desktop-demo/quota.html) 和 [视频演示](desktop-demo/video.html) 作为相机识别目标。

小手机优先使用 640×480 CameraX 实时预览。使用与 Mac 预览相同的 HTML 动效。Android 12 及以上用 GPU 合成渐进和全画面模糊，Android 12 上额外刷新模糊缓存以保持画面更新。单次 JPEG 抓取的目标分辨率仍为约 1280×960，拍摄内容与动效分开。

## 私有调试配置

使用 JDK 17、Android SDK Platform 37.0、Build Tools 36.0.0 和 Platform Tools。配置 `JAVA_HOME` 和 `ANDROID_HOME`，或由 Android Studio 写入 SDK 路径。

编辑工程根目录的 `local.properties`，保留已有的 `sdk.dir` 和方舟配置。以下是字段示例，真实 Key 只留在本机。

```properties
ARK_API_KEY=
ARK_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
ARK_MODEL=doubao-seed-2-1-lite-260915
SPEECH_API_KEY=
SPEECH_TTS_RESOURCE_ID=seed-tts-2.0
SPEECH_TTS_SPEAKER=
```

- 方舟 Key 用于单帧图文路由和短录音转写兜底。方舟地址也可填写完整的 HTTPS Chat Completions URL。
- 视觉问答优先复用下方的数字吧唧 Live 凭证和音色。方舟返回的答案以文本传给 Live 播报，收音仍由视觉模块处理。Live 在出声前不可用时，依次尝试系统中文 TTS、腾讯云合成及豆包语音合成 2.0。最后一项使用 `SPEECH_API_KEY`。`SPEECH_TTS_SPEAKER` 留空使用 `zh_female_vv_uranus_bigtts`，可改为控制台中已授权的音色。

腾讯云实时语音识别独立读取工程根目录的 `tencent-asr.local.properties`。请先在腾讯云开通语音识别，再在本机填写：

```properties
TENCENT_ASR_APP_ID=
TENCENT_ASR_SECRET_ID=
TENCENT_ASR_SECRET_KEY=
TENCENT_TTS_VOICE_TYPE=101001
```

系统语音缺失或已确认不给中间转写时，应用用腾讯云普通话引擎 `16k_zh` 边说边出字。系统中文播报不可用时，优先使用现有腾讯云凭证调用实时语音合成，默认普通话音色为「智瑜」101001。腾讯云账号需要单独开通语音合成，受限子账号需具备对应调用权限。腾讯云未配置、连接失败或未能返回最终结果时，应用可用同一段录音走方舟短音频兜底。只有方舟 Key 时仍可说完整句后自动提交。[腾讯云标准实时语音识别](https://cloud.tencent.com/document/api/1093/48982)

豆包端到端实时语音独立读取工程根目录的 `doubao-live.local.properties`。填入同一个已开通端到端实时语音应用的两项凭证。

```properties
doubao.appId=
doubao.apiKey=
```

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.musepocket.ai.vision/com.musepocket.ai.MainActivity
adb logcat -s MuseTiming
```

`MuseTiming` 仅记录毫秒耗时，包括预览就绪、麦克风就绪、首条中间转写、最终转写、抓帧、方舟回包、生成动画实际显示时长、结果显示和播报开始。

`local.properties`、`tencent-asr.local.properties` 和 `doubao-live.local.properties` 都被 Git 忽略。配置 Key 后，私有 Debug APK 会包含凭证。需要无凭证包时，在构建命令末尾加 `-PmuseOmitKeys`。未来公开分发时，应把 [方舟请求](app/src/main/java/com/musepocket/ai/VisionRouter.kt)、[实时转写](app/src/main/java/com/musepocket/ai/TencentStreamingAsr.kt)、[云端合成](app/src/main/java/com/musepocket/ai/VisionVoice.kt) 和 [Live 连接](app/src/main/java/com/musepocket/ai/live/voice/DoubaoRealtimeClient.kt) 切换到安全代理。

## 验收记录

- 竖屏 V2 已在 Android 12 真机通过 V1/V2 共存、音量减、短录音转写、两种跳转、物体和软件问答、遮挡、断网及静音重试。上一版私有 APK 已在构建输出目录的 `portrait-baseline` 归档。
- API 37 模拟器以 854×384 验证实时预览裁切、收音底部模糊、生成模糊、播报态隐藏转写、1280×960 JPEG 和两个结果页关闭。结果页采用注入测试结果验证。最终 APK 在同尺寸模拟器验证了屏幕入口、音量减和注入的标准 CAMERA 键事件。两秒显示下限和语音帧协议由单测覆盖。
- 0.4.0 共 14 项单测通过，Lint 0 错误。已使用本机腾讯云配置，在 JVM 运行 APK 使用的 Kotlin 转写模块。合成普通话音频返回 4 条中间结果和预期最终文本。
- 0.4.0 已在 S17 Pro mini 真机安装并启动。一次有声测试中，麦克风 120 ms 就绪，从启动收音到首条腾讯云中间结果 3,334 ms（包含用户开口前的等待），自动判停到 JPEG 就绪 825 ms。该轮未拿到最终转写，方舟短录音兜底提示「没有听清语音」，未进入播放器。
- ADB 捕获到实体拍照键的 `KEY_CAMERA` 事件，独立核验 App 前台派发和手动结束仍待补测。目标机未发现系统 TTS，当前私有包未配置云端播报。

- 0.5.0 真机反馈确认基本流程可用。0.5.1 聚焦静音底光、生成流光和文字回答样式，中文语音播报暂缓验收。
- 0.6.0 单 APK 已在 S17 Pro mini 安装并启动，V1 与 V2 包均在。854×384 模拟器已检查左下角、音量加、返回及角色画面。21 项单测通过，Lint 0 错误。用合成中文输入验证豆包 Live 服务完成转写和返回 PCM。真机实说实听待验收。
- 0.7.0 已安装并启动于 S17 Pro mini。24 项单测通过，Lint 0 错误。854×384 模拟器使用注入转写，验证首句切片采样、腹部气泡、工作动画到游戏约 3.2 秒、Flying Bird 点按和碰撞，以及慧记的面板显示和录音暂停、继续。真机实际口述和离线转写效果待验收。
- 0.7.1 在 S17 Pro mini 上测得慧记左卡片修复前 x=142、修复后 x=82，与 Muse 的 512×384 左卡片对齐。Live 路由改为最终转写上的动作和目标词组合，支持常见口语、同音转写、否定与普通提问过滤。
- 0.8.0 在 S17 Pro mini 上通过视觉 WebView 覆盖绘制、真实后摄打开和退出释放、注入转写与生成首帧验证。条纹测试确认中线清晰、下半屏逐步变柔，生成时上半屏也实际模糊。已验证 APK 内动效资源与 Mac runtime 字节一致。真人语音和在线模型流程待实测。
- 0.8.1 已安装至 S17 Pro mini。25 项单测通过，Lint 无错误。真机通过四宫格进入时的系统状态栏检查、后摄启动、返回释放、两种模糊状态下的新帧显示，以及测试句的音频播放。方舟在线验证合成杯子图、追问和不匹配指令回到问答。真人口述和角色字幕视觉效果待验收。
