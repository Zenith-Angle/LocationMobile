# LocationMobile

一个基于 Kotlin 和 Cesium 的 Android 移动定位应用，实现 GPS 定位并在三维地球上实时展示位置和轨迹。

## 功能特性

- GPS 精确定位
- Cesium 三维地球可视化
- 实时位置跟踪
- 移动轨迹记录和显示
- 多地图切换（卫星影像 / 街道地图 / 天地图）
- 后台定位自动降频（节省电量）
- 平滑视角飞行动画
- 完善的权限管理

## 技术栈

- **语言**: Kotlin
- **构建工具**: Gradle 9.3.1
- **Android SDK**: 26-34 (Android 8.0 - Android 14)
- **定位服务**: Google Play Services Location 21.1.0
- **地图引擎**: Cesium.js 1.112

## 快速开始

### 环境要求

- IntelliJ IDEA 或 Android Studio
- JDK 17
- Android SDK (API 26+)

### 配置 Token

1. 复制 Token 模板文件：
   ```bash
   cp app/tokens.properties.example app/tokens.properties
   ```

2. 编辑 `app/tokens.properties`，填入你的 Token：
   ```properties
   CESIUM_ION_TOKEN=你的CesiumIonToken
   TIANDITU_TOKEN=你的天地图Token（可选）
   ```

3. Sync/重新构建后运行 `debug` 版本（Token 在构建期注入到 BuildConfig）

4. 安全说明：`release` 默认不会注入 Token，避免随生产包分发

### Token 申请

- **Cesium Ion**: https://cesium.com/ion/tokens
- **天地图**: https://console.tianditu.gov.cn

## 主要功能

### 单次定位
- 点击"获取位置"按钮获取当前位置
- 地图视角飞行到当前位置
- 显示红色位置标记和精度圈

### 持续跟踪
- 点击"开始跟踪"开始实时定位
- 每 5 秒更新一次位置
- 绘制青色发光轨迹线
- 显示蓝色实时位置标记

### 后台定位
- 应用进入后台时，定位频率自动减半（间隔从 5 秒增至 10 秒）
- 返回前台时自动恢复正常频率

### 地图切换
- 点击左上角"图层"按钮
- 可切换：卫星影像 / 街道地图 / 天地图

## 项目结构

```
app/src/main/
├── java/com/locationmobile/
│   └── MainActivity.kt          # 主活动（定位逻辑）
├── res/
│   ├── layout/activity_main.xml # 布局文件
│   ├── mipmap-xxxhdpi/        # 应用图标
│   └── values/                 # 资源文件
├── assets/
│   ├── cesium/index.html      # Cesium 地图页面
│   └── 定位.svg               # 定位图标
└── AndroidManifest.xml        # 应用清单
```

## 权限说明

- `INTERNET` - 加载 Cesium 地图
- `ACCESS_FINE_LOCATION` - 精确 GPS 定位
- `ACCESS_COARSE_LOCATION` - 粗略定位

## 版本

当前版本：**1.1.3** (versionCode: 2)

## 注意事项

1. 必须配置 Cesium Token 才能加载地图
2. 需要网络连接
3. GPS 定位建议在户外测试
4. 持续定位较耗电，使用后记得停止

## 许可证

本项目仅供学习和参考使用。
