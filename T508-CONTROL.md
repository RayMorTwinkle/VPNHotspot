# t508 控制面 fork（t508-control-plane 分支）

基线：上游 Mygod/VPNHotspot master（6e3d4a4f，v3.0.8 开发态）。
本分支为 TCL T508N 无头部署加了一套 shell 驱动的控制面，不改变任何 GUI 行为。

## 新增了什么

### 1. ControlService（shell 控制面）

`mobile/src/main/java/be/mygod/vpnhotspot/control/ControlService.kt`

- **不导出**（non-exported）：只有 shell（adb，uid 2000）和 root 能启动它，
  普通 app 无法调用——无需自造权限
- 走 `am start-foreground-service` 触发，动作化 intent：

| 动作 | 作用 |
|------|------|
| `be.mygod.vpnhotspot.control.STATUS` | 把 tethered/available/localOnly 接口、AP 配置（SSID/加密/自动关闭）、wlan0 地址写入文件日志 |
| `be.mygod.vpnhotspot.control.SETUP` | 可选 extras 设 AP 配置（ssid/password/security=open\|wpa2\|wpa3/hidden）→ `startTethering(WIFI)`（自带 root 回退）→ 启动 TetheringService 接管 wlan0 |
| `be.mygod.vpnhotspot.control.TEAR_DOWN` | 停止管理 wlan0 + `stopTethering(WIFI)` |
| `be.mygod.vpnhotspot.control.CLEAN` | 全局路由清理（上游 RoutingManager.clean） |

### 2. 文件日志（关键调试能力）

- `App.kt` 新增 `FileLogTree`：全量 Timber 日志（含 INFO 级，release 版 logcat 看不到）
  追加写入 `files/debug.log`，>4MB 截断保留尾部 1MB
- `ControlService` 另写 `files/control.log`（STATUS 结果落这里），>2MB 截断保留 512KB
- 读取（root）：
  `adb shell su -c 'cat /data/data/be.mygod.vpnhotspot/files/debug.log'`
- 上游 release 的 INFO 日志只在 Crashlytics，无 GMS 的设备（如 T508N）等于没有日志；
  fork 版任何 root 侧失败（之前 82ms 静默回滚的那种）都能在文件里看到原因

## 使用示例

```bash
# 状态（结果在 control.log）
adb shell su -c "am start-foreground-service -n be.mygod.vpnhotspot/.control.ControlService -a be.mygod.vpnhotspot.control.STATUS"
sleep 2
adb shell su -c 'cat /data/data/be.mygod.vpnhotspot/files/control.log' | tail -20

# 一条命令拉起整条链（AP 配置 + 系统 tethering + wlan0 接管）
adb shell su -c "am start-foreground-service -n be.mygod.vpnhotspot/.control.ControlService -a be.mygod.vpnhotspot.control.SETUP --es ssid T508N-Net --es password 88880000 --es security wpa2"

# 拆链
adb shell su -c "am start-foreground-service -n be.mygod.vpnhotspot/.control.ControlService -a be.mygod.vpnhotspot.control.TEAR_DOWN"

# 路由清理
adb shell su -c "am start-foreground-service -n be.mygod.vpnhotspot/.control.ControlService -a be.mygod.vpnhotspot.control.CLEAN"

# 全量应用日志
adb shell su -c 'cat /data/data/be.mygod.vpnhotspot/files/debug.log' | tail -50
```

## 构建

```bash
git submodule update --init --depth 1 external/aosp/hardware/interfaces
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME=~/Library/Android/sdk
export PATH=$HOME/.cargo/bin:$PATH   # cargo-ndk + 4 个 android rust target
./gradlew :mobile:assembleRelease    # 产物 mobile/build/outputs/apk/release/
```

## 版本

`3.0.8-t508.1`（versionCode 2012）。applicationId 保持 `be.mygod.vpnhotspot`
（google-services.json 绑定此包名；装机时卸掉原版避免冲突）。

## 已知小瑕疵 / TODO

- STATUS 的 `ap config` 段报 InvocationTargetException：此 ROM 上 `WifiApManager.configuration`
  反射读取失败。**写路径正常**（SETUP 实证 `ssid="T508N-Net"` 落盘），只影响状态展示不影响功能。
  修法：STATUS 读配置也走 root 回退（`WifiApCommands.GetConfiguration`），下次改代码时顺手做。
- 卸载重装 fork 后首次启动可能撞 zygote CE 目录竞态（`Unable to find pkg:uid in /data_mirror`，
  进程 start timeout）。等待数秒重试 `am` 即自愈；必要时 root 手工 `mkdir -p /data/data/<pkg>`。

## 同步上游

```bash
git fetch upstream && git rebase upstream/master
```
