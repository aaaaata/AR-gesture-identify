# Gesture Link Demo

当前手势识别探索、最终架构、已验证能力和后续产品化边界，以
[`docs/gesture-exploration-summary.md`](docs/gesture-exploration-summary.md) 为准。其他文档保留了
不同阶段的实现说明，可能仍描述同 APK 私有服务等历史形态。

项目、SDK 与现成 Android XR Runtime 的简图见
[`docs/project-runtime-relationship.md`](docs/project-runtime-relationship.md)。详细的真机
集成说明见 [`docs/architecture.md`](docs/architecture.md)。

一个分阶段验证手势扩展能力的 Android XR Demo。当前同时保留 CameraX 单帧链路，并新增
通过 `RayNeoSDK-client.aar` 连接现成 Runtime 的实时手势识别链路：

```text
Android 应用 A / 应用 B
    -> gesture-sdk / GestureClient
    -> Binder/Messenger
    -> service-host APK 的 :gesture_engine 进程
       -> CameraX 单帧 -> Demo 识别器
       -> RayNeoSDK-client.aar -> Runtime -> YUV420 -> 实时手势识别
```

## 工程结构

- `app`：客户端 A，展示取景、诊断和识别结果。
- `consumer-app`：客户端 B，用于验证另一个应用也能调用同一手势服务。
- `service-host`：独立能力服务的 APK 安装载体，没有主界面。
- `gesture-sdk`：应用层 API，包括单图识别、Runtime 实时识别控制和状态模型。
- `gesture-service`：服务实现，持有 Runtime 客户端、`XRCamera`、YUV 转换和识别器。

应用 A 和 B 不直接引用 RayNeo AAR 类型或识别算法类型，只依赖 `gesture-sdk`。Runtime
图像由安装在 `service-host` 中的 Service 管理，应用通过 `GestureClient` 连接 Host、控制
实时识别并读取状态。

当前摄像头调用方式：

```kotlin
val cameraView = GestureCameraView(context)
val result = gestureClient.recognize(cameraView)
```

`GestureCameraView` 使用 Android CameraX/Camera2 系统服务，不调用厂商保密的 XR
Runtime。Android 系统相机服务是访问摄像头硬件不可省略的标准系统边界。

Runtime 实时识别调用：

```kotlin
val status = gestureClient.startRuntimeRecognition()
```

真机验证步骤见 [`docs/runtime-camera-probe.md`](docs/runtime-camera-probe.md)。

## 为什么使用 Demo 识别器

MediaPipe Tasks Vision Android AAR 的手势 JNI 仅包含 `arm64-v8a`，Windows 上常用的
Android XR 模拟器是 `x86_64`。为了让最小链路在模拟器中实际运行，当前版本使用纯
Kotlin 轮廓启发式识别器，不包含模型和硬件依赖。

它用于验证架构链路，不代表生产算法精度。当前受控条件为：

- 图片中只有一只竖直、完整的裸手；
- 背景尽量为纯色，并与肤色形成明显对比；
- 支持验证 `Open_Palm`、`Closed_Fist`、`Victory`、`Pointing_Up`；
- 不负责动态手势、3D 关节、左右手判断和系统级输入注入。

界面中的置信度是启发式规则评分，不是训练模型输出。结果同时包含图片尺寸和
像素指纹组成的“输入摘要”；更换图片后摘要应随之变化，可用于验证独立服务确实
处理了当前选择的输入，而不是复用上一次结果。

## 构建

环境要求：JDK 17、Android SDK 36。

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

项目配置了阿里云 Google Maven/中央仓库镜像，并保留官方仓库作为后备。

## Android XR 模拟器说明

`RayNeoSDK-client.aar` 只包含 `arm64-v8a` 原生库，因此当前 Runtime 真机版本的 APK 不再
支持 x86_64 Android XR 模拟器。原 CameraX 代码仍被保留且默认关闭，避免真机测试时与
Runtime 抢占摄像头；接下来可以通过单独构建变体恢复模拟器回归包。

## 这个 Demo 验证什么

- CameraX 路径可以通过 Android 标准相机栈取得真实摄像头单帧；
- 能力服务和 XR 应用可以跨进程传递拍摄结果与识别结果；
- 输入源、识别实现和应用 UI 已解耦；
- RayNeo Runtime 相机 API 已接入独立服务进程，YUV420 帧能够持续进入识别器并返回结果。

RayNeo X3 Pro 真机已验证 Runtime 1.1.7.9 可持续返回约 30 FPS 的 `640x400` YUV420 帧，
规则识别稳定耗时约 17-19 ms。当前不验证正式模型准确率、功耗、坐标系同步、OpenXR
扩展或系统输入注入。
