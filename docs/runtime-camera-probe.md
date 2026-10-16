# RayNeo Runtime 相机探针验证

## 验证位置

本探针必须安装到带有 `com.rayneo.xr.runtime` 的雷鸟 ARM64 真机。当前 APK 包含
`RayNeoSDK-client.aar` 的 ARM64 原生库，不能用 x86_64 Android XR 模拟器验证 Runtime
链路。

## 构建和安装

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.xrgesturedemo/.MainActivity
```

进入应用后保持 CameraX 回归输入关闭，将光标对准“启动实时识别”并单击镜腿。链路依次执行：

```text
GestureClient
  -> Binder/Messenger
  -> :gesture_engine
  -> RayNeoSDK-client.aar
  -> com.rayneo.xr.runtime
  -> XRCamera("0")
  -> YUV420 preview callback
  -> YUV420 复制、旋转和 RGB 转换
  -> DemoImageGestureRecognizer
  -> RuntimeCameraStatus
```

## 通过标准

- 状态最终进入 `STREAMING`；
- Runtime、摄像头均显示已连接/已打开；
- 帧数每秒增加；
- 时间戳持续变化；
- 手或场景移动后 `Checksum` 变化；
- 平均 FPS 不为零；
- “实时手势”随画面变化，推理次数持续增加；
- 稳定推理耗时不阻塞 30 FPS 相机回调；
- 停止后回到 `IDLE`，再次启动仍能恢复。

如果状态停在 `WAITING_FOR_FRAME`，说明 Runtime 已创建预览通道但没有回调图像。若进入
`ERROR`，界面的“详情”会显示失败所在步骤和底层异常。

## 查看日志

```powershell
adb logcat -s RayNeoCameraProbe RayNeoXR AndroidRuntime
```

`RayNeoCameraProbe` 会记录状态变化、第一帧以及每 30 帧的尺寸、时间戳和 checksum。

## 真机验证结果

RayNeo X3 Pro（Runtime 1.1.7.9）实测：相机 `0` 返回三平面 I420，row stride 为
`640/320/320`，方向为 `90°`。相机稳定约 30 FPS；每 6 帧执行一次规则识别，稳定推理耗时
约 17-19 ms。当前 Service 虽在独立进程中，但仍随 Demo APK 安装且 `exported=false`，尚
不是可供多个应用共享的独立服务 APK。
