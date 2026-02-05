# LocationMobile

一个基于Kotlin和Cesium的Android移动定位应用,实现GPS定位并在三维地球上实时展示位置和轨迹。

## 📱 功能特性

- ✅ GPS精确定位
- ✅ Cesium三维地球可视化
- ✅ 实时位置跟踪
- ✅ 移动轨迹记录和显示
- ✅ GPS精度圆圈显示
- ✅ 平滑视角飞行动画
- ✅ 完善的权限管理

## 🛠️ 技术栈

- **语言**: Kotlin 1.9.22
- **构建工具**: Gradle 8.5
- **Android SDK**: 24-34 (Android 7.0 - Android 14)
- **JDK**: 17
- **定位服务**: Google Play Services Location 21.1.0
- **前端**: Cesium.js 1.112
- **协程**: Kotlinx Coroutines 1.7.3

## 🚀 快速开始

### 1. 环境要求

- IntelliJ IDEA 2023.3+ 或 Android Studio
- JDK 17
- Android SDK (API 24+)
- Cesium Ion账户(免费)

### 2. 获取Cesium Token

1. 访问 https://cesium.com/ion/signup 注册账户
2. 登录后访问 https://cesium.com/ion/tokens
3. 创建新的访问令牌(Access Token)
4. 复制令牌

### 3. 配置项目

1. 用IntelliJ IDEA或Android Studio打开项目
2. 打开 `app/src/main/assets/cesium/index.html`
3. 找到:
   ```javascript
   Cesium.Ion.defaultAccessToken = 'YOUR_CESIUM_ION_ACCESS_TOKEN';
   ```
4. 替换为你的实际令牌

### 4. 同步和运行

1. 等待Gradle同步完成
2. 连接Android设备或启动模拟器
3. 点击运行按钮 ▶
4. 授予定位权限
5. 测试定位功能

## 📖 主要功能

### 单次定位
- 点击"📍 获取位置"按钮
- 获取当前GPS位置
- 地图视角飞行到当前位置
- 显示红色位置标记和精度圆圈

### 持续跟踪
- 点击"🎯 开始跟踪"按钮
- 每5秒更新一次位置
- 绘制青色发光轨迹线
- 显示蓝色实时位置标记
- 点击"⏸️ 停止跟踪"停止

### 清除标记
- 点击"🗑️ 清除标记"按钮
- 清除所有位置标记和轨迹线

## 📂 项目结构

```
LocationMobile/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/locationmobile/app/
│   │       │   └── MainActivity.kt           # 主活动
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   └── activity_main.xml     # 布局文件
│   │       │   └── values/
│   │       │       └── strings.xml           # 字符串资源
│   │       ├── assets/
│   │       │   └── cesium/
│   │       │       └── index.html            # Cesium地图页面
│   │       └── AndroidManifest.xml           # 应用清单
│   ├── build.gradle                          # 应用构建配置
│   └── proguard-rules.pro                    # 混淆规则
├── build.gradle                              # 项目构建配置
├── settings.gradle                           # Gradle设置
├── gradle.properties                         # Gradle属性
└── README.md                                 # 本文件
```

## 🔑 权限说明

应用需要以下权限:

- `INTERNET` - 加载Cesium地图库
- `ACCESS_NETWORK_STATE` - 检查网络状态
- `ACCESS_FINE_LOCATION` - 获取精确GPS定位
- `ACCESS_COARSE_LOCATION` - 获取粗略定位

## 🐛 调试技巧

### 查看日志
在IntelliJ IDEA或Android Studio底部的Logcat中:
- 过滤标签: `LocationMobile`
- 查看应用运行日志

### 模拟器定位测试
1. 启动模拟器
2. 点击右侧"..."按钮
3. 选择"Location"
4. 输入测试坐标
5. 点击"Send"

### WebView调试
在Chrome浏览器中:
1. 输入 `chrome://inspect/#devices`
2. 找到你的设备
3. 点击"inspect"调试WebView

## ⚙️ 配置说明

### local.properties

如果Gradle同步失败,创建 `local.properties` 文件:

```properties
# Windows
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk

# macOS
# sdk.dir=/Users/你的用户名/Library/Android/sdk

# Linux
# sdk.dir=/home/你的用户名/Android/Sdk
```

## 📝 注意事项

1. **必须配置Cesium Token** - 否则地图无法加载
2. **需要网络连接** - 首次加载需要下载Cesium库
3. **GPS定位精度** - 室内定位精度较低,建议户外测试
4. **持续定位耗电** - 使用后记得停止跟踪

## 🔧 常见问题

### Q: Gradle同步失败?
A: 检查网络连接,确认JDK版本为17,查看错误日志

### Q: 地图显示空白?
A: 检查Cesium Token是否配置,检查网络连接,查看Logcat日志

### Q: 无法获取定位?
A: 确保已授予定位权限,GPS已开启,在户外测试

### Q: Run按钮是灰色?
A: 等待Gradle同步完成,或手动创建运行配置

## 📄 开源协议

本项目仅供学习和参考使用。

## 🤝 贡献

欢迎提交Issue和Pull Request!

## 📞 技术支持

- **Android开发文档**: https://developer.android.com/
- **Kotlin文档**: https://kotlinlang.org/
- **Cesium文档**: https://cesium.com/docs/

---

**祝你使用愉快!** 🎉
