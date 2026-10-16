# 手势扩展 Demo 架构说明

## 1. 一句话定位

本项目验证了一条不修改厂商 Android XR Runtime 的扩展能力链路：

```text
XR 应用 -> Gesture SDK -> Binder IPC -> 独立手势服务 -> 识别引擎 -> GestureResult
```

当前保留 CameraX 单帧输入和纯 Kotlin 轮廓规则，同时在独立 Service 中加入了
`RayNeoSDK-client.aar` Runtime 实时相机输入。YUV420 帧已接入现有手势识别器。

> `:gesture_engine` 是当前 APK 中运行自研识别服务的独立进程，不是 Android XR/OpenXR
> Runtime，也没有与厂商 Runtime 合并。

## 2. 工程结构

```text
android-xr-gesture-demo/
├── app/                 XR Demo 应用与 Compose UI
├── gesture-sdk/         给应用调用的稳定 API 和结果模型
├── gesture-service/     独立进程服务、单帧处理和识别实现
└── docs/                架构说明与架构图
```

### `app`

职责：

- 申请标准 Android 相机权限；
- 承载 SDK 提供的 `GestureCameraView` 实时预览；
- 创建和关闭 `GestureClient`；
- 调用 `GestureClient.recognize(cameraView)`；
- 管理加载、成功和错误状态；
- 展示手势标签、规则分数、耗时、引擎和输入指纹。

它不直接引用 CameraX 或识别器类型，也不需要知道算法位于另一个进程。

入口：`app/src/main/java/com/example/xrgesturedemo/MainActivity.kt`

### `gesture-sdk`

这是应用开发者应该接触的模块，也是后续最需要保持兼容的边界。

- `GestureCameraView`：SDK 内封装 CameraX 前置摄像头预览和单帧拍摄；
- `GestureClient`：绑定能力服务、提交拍摄结果并等待异步结果；
- `GestureResult`：稳定的结果 DTO，不暴露具体算法类型；
- `GestureIpcContract`：当前基于 Messenger 的内部消息协议。

当前公开调用：

```kotlin
val client = GestureClient(context)
val result: GestureResult = client.recognize(cameraView)
```

`GestureResult` 当前包含：

```text
label, confidence, handedness, featureCount,
inferenceTimeMs, engine, inputFingerprint
```

其中 `confidence` 在当前 Demo 中是规则评分，不是模型概率。

### `gesture-service`

职责：

- 作为 Android Bound Service 接收识别请求；
- 运行在 `android:process=":gesture_engine"` 指定的独立进程；
- 初始化并绑定 RayNeo Runtime，持有 `XRCamera` 和预览通道；
- 复制、旋转和转换 YUV420 帧，并限频运行实时识别；
- 返回 Runtime、相机、帧和实时手势诊断状态；
- 从 `ParcelFileDescriptor` 解码图片并处理 EXIF 方向；
- 调用 `DemoImageGestureRecognizer`；
- 通过 Messenger 返回成功或错误消息。

当前服务 `exported=false`，并且三个 Gradle 模块最终合并进同一个 APK。因此它目前是
应用内部的进程隔离，不是多个第三方应用共享的系统服务。

## 3. 一次识别请求如何流动

```text
1. GestureCameraView 通过 Android CameraX/Camera2 显示 `webcam0` 预览
2. 应用调用 GestureClient.recognize(cameraView)
3. SDK 拍摄单帧 JPEG，并打开 ParcelFileDescriptor
4. GestureClient 发送 MSG_RECOGNIZE，并将 replyTo 作为回调通道
5. Binder 将文件描述符传给 :gesture_engine 进程
6. GestureRecognitionService 解码、旋转和缩放图片
7. DemoImageGestureRecognizer 输出类别、规则分数和特征数
8. Service 返回 MSG_RESULT 或 MSG_ERROR
9. GestureClient 删除临时 JPEG，并还原为 GestureResult
10. MainActivity 更新 Compose UI
```

这里传输的是文件描述符，不是把整张 Bitmap 塞进 Binder，因此不会直接撞上 Binder
事务大小限制。

## 4. 与 Android XR 和厂商 Runtime 的关系

需要区分三种不同含义的“Runtime”：

| 层次 | 负责什么 | 本项目是否修改它 |
|---|---|---|
| 厂商/Android XR 系统 Runtime | 驱动、相机/IMU、头手追踪、坐标系、显示合成、权限、系统输入 | 否 |
| OpenXR Runtime | 实现 OpenXR 标准接口并连接设备能力 | 否 |
| 本项目 `:gesture_engine` | APK 内运行识别算法的普通 Android 服务进程 | 是，本项目自己的代码 |

正确的集成关系不是“两个 Runtime 合并”，而是：

```text
应用和扩展 Gesture SDK
        ↓
扩展手势服务 / 识别引擎
        ↓ 只通过公开接口取数据
厂商 SDK / Jetpack XR / ARCore for Jetpack XR / OpenXR
        ↓
厂商保密 Runtime、Android XR 系统和硬件
```

### 可用的硬件输入路径

真实眼镜至少需要开放以下一种数据，扩展识别才有输入。当前已经从 ARDK AAR 中还原并
接入第一种路径，但真机授权与回调仍待验证：

1. 相机图像帧：扩展服务自行做人手检测、关键点和手势分类；
2. 手部关键点或关节姿态：扩展服务只做手势分类和时序识别；
3. 厂商已有的基础手势事件：扩展服务只能在已有事件基础上组合高级行为；
4. 设备支持的 Jetpack XR/ARCore for Jetpack XR 手部追踪数据；
5. 设备公开的 OpenXR 手部追踪扩展，适合 Native/OpenXR 路径。

如果厂商既不开放相机帧，也不开放手部关键点或基础事件，上层 SDK 无法凭空补出
自定义手势识别能力。

### 应用级手势与系统级手势

- 应用级手势：识别结果只返回接入 Gesture SDK 的应用。当前架构可以实现。
- 系统级手势：控制桌面、返回、窗口或向其他应用注入输入。通常需要系统权限、平台
  签名或厂商 Runtime/系统服务支持，普通扩展服务不能自行完成。

## 5. Demo 已经证明和没有证明的内容

已经证明：

- 应用层只依赖稳定 SDK API；
- 图片句柄能够跨进程传输；
- 能力服务可以与 UI 进程隔离；
- 后端处理了当前输入并返回结构化结果；
- 后端算法可替换而不要求应用改写调用流程；
- RayNeo X3 Pro 上 Runtime 相机、YUV 转换和实时规则识别链路已经实际运行。

尚未证明：

- Runtime/客户端 AAR 小版本不一致时的长期兼容性；
- 相机和手部数据的授权方式；
- 连续帧吞吐量、功耗、发热和端到端延迟；
- 时间戳、头部坐标系、相机坐标系和世界坐标系同步；
- 服务死亡、系统休眠、焦点切换和后台限制下的恢复；
- 系统级手势注册、冲突处理和输入注入权限；
- 正式模型的准确率、量化和目标芯片 ABI/加速器兼容性。

因此当前结论应表述为：**扩展能力的软件分层与调用链路可行，硬件接入和产品化指标仍需
在真机与厂商公开接口上验证。**

## 6. 从 Demo 演进到真机版本

### 阶段 A：摄像头单帧链路（当前完成）

CameraX 从电脑摄像头拍摄单帧；保留 `GestureClient`、Binder 调用、服务进程和结果 DTO，
确认端到端链路。

### 阶段 B：接入一种真实数据源（已完成）

`RayNeoRuntimeCameraSource` 已加入 `gesture-service`，通过 `RayNeoSDK-client.aar` 请求
`XRCamera("0")` 的 YUV420 预览，并记录帧率、时间戳、尺寸和 checksum。RayNeo X3 Pro
真机已验证约 30 FPS。

### 阶段 C：实时规则识别（已完成）

每 6 帧复制一次 Runtime 缓冲区，在独立推理线程完成相机方向校正、YUV420 到 RGB 转换和
规则识别。应用每秒通过 SDK 查询最新结构化结果，镜腿单击即可启动或停止。

### 阶段 D：把轮询 API 扩展为事件流 API

动态手势不能靠单张图片完成。SDK 需要增加类似接口：

```kotlin
interface GestureSession : AutoCloseable {
    val events: Flow<GestureEvent>
}

fun GestureClient.startRecognition(config: GestureConfig): GestureSession
```

同时定义取消、背压、掉线重连、超时、线程和生命周期语义。

### 阶段 E：替换正式模型并真机验收

只替换 Engine 内部实现，评估准确率、延迟、内存、功耗、温升和芯片加速支持。SDK 不应
暴露 MediaPipe、TensorFlow Lite 或厂商推理框架的内部类型。

### 阶段 F：决定部署形态

- 仅供一个应用使用：可以继续随应用 APK 内置；
- 供多个应用使用：拆成独立、签名受控的服务 APK，使用权限保护和稳定 AIDL；
- 要成为系统交互：需要厂商提供系统扩展点，不能只靠普通应用服务实现。

## 7. 下一步应向厂商确认的问题

1. 是否可以获取相机帧、手部关键点或追踪姿态，数据格式和频率是什么？
2. 接口属于厂商 Android SDK、Jetpack XR API 还是 OpenXR 扩展？
3. 是否允许后台持续访问，应用失焦或进入 Full Space 后行为如何？
4. 时间戳基准和坐标系如何定义，能否与头部姿态同步？
5. 支持哪些 ABI、GPU/NPU/DSP，以及模型部署限制是什么？
6. 是否存在系统级手势注册机制，如何处理与系统手势的冲突？
7. 服务是否允许跨应用共享，需要哪些签名权限或白名单？
8. API 的版本、兼容策略和错误恢复机制是什么？

回答完这些问题后，才能决定下一阶段是接相机图像、接手部关键点，还是直接消费厂商
已有的手势事件。
