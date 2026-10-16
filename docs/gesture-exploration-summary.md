# 手势识别扩展 Demo 总结

## 1. Demo 定位

该 Demo 用于验证：无需修改现有 RayNeo Runtime，即可通过其相机接口，在上层构建一个支持
多个 Android 应用调用的手势识别 SDK 与服务链路。

## 2. 参考链路

项目参考的是 **RayNeo OpenXR Unity ARDK 1.1.2**：

```text
Unity Package：com.unity.xr.rayneo.openxr
Package 名称：RayNeo OpenXR ARDK
版本：1.1.2
参考示例：Samples~/HelloRayNeo
```

示例中的主要相机调用链为：

```text
HelloRayNeo/ShareCameraCtrl.cs
    -> ShareCamera.OpenCamera(...)
    -> ShareCamera.cs
    -> XRCamera.open()
    -> XRCamera.startPreviewChannelWithParameters(...)
    -> XRInterfaces.RayNeoApi_OpenCameraDevice(...)
    -> XRInterfaces.RayNeoApi_StartPreviewChannelWithParameters(...)
    -> libRayNeoXRUnityInterfaces.so
    -> RayNeo Runtime
    -> 眼镜相机帧回调
```

关键代码分别位于：

- `Samples~/HelloRayNeo/Scripts/Interactive/ShareCameraCtrl.cs`：示例入口；
- `SDK/Runtime/Scripts/APIs/ShareCamera/ShareCamera.cs`：打开相机并启动预览；
- `OXR/Runtime/Scripts/Extensions/public/device/XRCamera.cs`：相机设备封装；
- `OXR/Runtime/Scripts/Extensions/public/core/XRInterfaces.cs`：声明底层 native 接口。

这条链路证明 Runtime 已经向上提供相机访问和连续图像帧能力。Android Demo 没有依赖 Unity，
也没有直接调用 `RayNeoApi_*`，而是使用配套 `RayNeoSDK-client.aar` 中的 Android 封装实现
对应流程：连接 Runtime、查询并打开相机、启动 YUV420 预览通道、接收图像帧回调。

## 3. Demo 架构

```text
Android 应用
    ↓
手势 SDK
    ↓
手势识别服务
    ↓
RayNeo Runtime
    ↓
眼镜相机
```

- **Android 应用**：使用手势能力并展示结果；
- **手势 SDK**：向应用提供统一调用接口；
- **手势识别服务**：获取图像、执行识别并返回结果；
- **RayNeo Runtime**：管理相机等设备能力；
- **眼镜相机**：提供真实图像数据。

## 4. 验证了什么能力

该 Demo 已验证：

- 应用可以通过 SDK 调用独立的手势识别服务；
- 服务可以通过现有 Runtime 获取真实、连续的眼镜相机画面；
- 相机图像可以进入识别算法，并将手势结果返回应用；
- 多个主动接入 SDK 的 Android 应用可以共享同一个手势服务；
- 识别停止或应用退出时可以释放相机资源。

因此，当前已经跑通：

```text
应用调用 -> SDK -> 手势服务 -> Runtime 相机 -> 手势识别 -> 结果返回
```

该 Demo 尚未验证系统级全局手势、桌面控制或向任意未接入 SDK 的应用注入输入。

## 5. 使用或验证的 Runtime 接口

Demo 主要使用了 `RayNeoSDK-client.aar` 提供的以下 Runtime Client 能力：

- 初始化并连接 Runtime；
- 查询相机设备、支持的分辨率和相机方向；
- 打开和关闭相机；
- 启动和停止相机预览通道；
- 通过回调持续接收 YUV420 图像帧。

核心接口包括：

```text
FxrApi.initAndroid()
FFalconXRClient.bindService()
XRCameraHelper
XRCamera.open() / close()
startPreviewChannelWithParameters()
stopPreviewChannel()
ICameraImageAvailable
```

这些属于 Runtime 对上层提供的客户端接口，不是 Runtime 内部源码接口。

## 6. 与正式版的区别

当前 Demo 的目标是验证链路，识别算法只是简单规则实现，接口协议、生命周期和性能也只满足
测试需要。

正式版还需要：

- 使用经过训练和评测的正式手势识别模型；
- 将轮询状态改为稳定的手势事件订阅；
- 完善多应用并发、服务重连和异常恢复；
- 优化延迟、功耗、温升和内存占用；
- 建立 Runtime 与 SDK 的版本兼容策略；
- 完善权限、隐私、API 文档和 SDK 发布方式。

正式版仍可保留当前总体架构：

```text
Android 应用 -> 手势 SDK -> 手势识别服务 -> RayNeo Runtime -> 眼镜相机
```

如果目标是控制系统桌面或任意应用，还需要 Runtime/OS 团队提供系统级权限和正式扩展接口。
