# PC 端打包（`pack/`）

在 `TextSend_Desktop` 目录下跑。需要 **JDK 17+**（带 `jpackage`）和 **Maven**，都在 PATH 里。

产物都在 `dist/`。

## 一键

**Linux / macOS（bash）：**

```bash
./pack/pack.sh
```

**Windows（PowerShell）：** 在 `TextSend_Desktop` 里执行

```powershell
powershell -ExecutionPolicy Bypass -File .\pack\pack.ps1
```

或当前用户已允许脚本时：

```powershell
.\pack\pack.ps1
```

会打出 fat JAR、绿色目录（自带精简 JRE），再按**当前操作系统**打安装包：

| 你在哪台机器上跑 | 额外安装包 |
|------------------|------------|
| Linux（`pack.sh`） | `.deb`（Debian / Ubuntu） |
| macOS（`pack.sh`） | `.dmg` |
| Windows（`pack.ps1`） | `.exe` 安装程序 |

**不能交叉编译。** Linux 上打不出 Windows 的 exe，也打不出 Mac 的 dmg。要哪种包，就在哪种系统上跑一遍。

## 分项脚本

Linux / macOS 用 `.sh`；Windows 用同名 `.ps1`。

| 文件 | 用途 | 产物 | 在哪打 |
|------|------|------|--------|
| `common.sh` / `common.ps1` | 被其它脚本引入：读版本号、路径、检查命令 | 无 | — |
| `pack-jar.sh` / `pack-jar.ps1` | Maven shade，一个 fat JAR | `dist/Textsend_<版本>.jar` | 任意有 JDK+Maven 的系统 |
| `pack-appimage.sh` / `pack-appimage.ps1` | **不是** Linux `.AppImage`。是 `jpackage --type app-image` 绿色目录 | Linux/mac：目录 + `.tar.gz`；Windows：`dist\TextSend\` + `.zip` | 目标系统 |
| `pack-deb.sh` | Debian 安装包 | `dist/*.deb` | **只能 Linux**（还要 `fakeroot`、`dpkg-deb`） |
| `pack-mac.sh` | macOS 磁盘镜像 | `dist/*.dmg` | **只能 macOS** |
| `pack-win.ps1` | Windows 安装程序 | `dist/*.exe` | **只能 Windows PowerShell** |
| `pack.sh` / `pack.ps1` | 上面几项按平台串起来 | 见上 | 见上 |

`pack.sh` / `pack.ps1` 会设 `SKIP_JAR=1`，避免绿色目录 / 安装包再 Maven 一遍。

## 产物怎么用

- **JAR**：对方要装 Java 17+。`java -jar dist/Textsend_5.0.56.jar`（版本号随 `TextSendMain.VERSION`）
- **绿色目录**：解压即用，不必装系统 Java。Linux：`TextSend/bin/TextSend`；Mac：`TextSend.app`；Windows：`TextSend\TextSend.exe`
- **.deb**：`sudo dpkg -i dist/textsend_*.deb`，一般装到 `/opt`
- **.dmg / .exe**：本机安装器用。Mac 的 dmg 未签名，可能要右键打开

配置文件：程序目录能写就写旁边的 `textsend.properties`；系统安装包写不了程序目录时，退回用户主目录 `.textsend.properties`。

## 图标

| 用途 | 文件 | 说明 |
|------|------|------|
| 打包（Linux deb / 绿色目录） | `other/icon.png` | `jpackage --icon` |
| 打包（Windows exe） | `other/icon.ico` | 已从 png 重新生成 |
| 打包（macOS dmg） | `other/icon.icns` | 打 dmg 时若无则从 png 自动生成 |
| 运行中窗口 / 任务栏 | `src/cn.rmshadows.TextSend/resources/icon.png` | 打进 fat JAR，`AppIcons` 加载 |

**fat JAR 文件本身**在文件管理器里仍是 Java 归档图标；运行后窗口和任务栏会有应用图标。`.deb` / 绿色目录 / 安装包菜单项靠 `jpackage --icon`。

**GNOME 任务栏**：jpackage 默认 `.desktop` 不含 `StartupWMClass`，Dock 会显示通用 Java 图标。已用 `pack/jpackage-resources/TextSend.desktop`（`StartupWMClass=TextSend`）+ 运行时 `LinuxWmClass` 固定 WM_CLASS。改 deb 后需重装；若菜单仍无条目可执行 `xdg-desktop-menu install /opt/textsend/lib/textsend-TextSend.desktop`。

## 还没有的

- **Linux `.AppImage`**（单文件）：和现在的绿色目录不是一回事，没做
- **Windows `.msi`**：要 WiX。现在只打 `.exe`
- **macOS 签名 / 公证**：没有开发者证书就不做，本机用可以
- **rpm / 跨架构**（例如在 x64 上打 arm64）：jpackage 不做

## 依赖备忘

- 所有脚本：`java`（17+）、`mvn`
- 绿色目录和安装包：`jpackage`（JDK 里自带，PATH 要能找到）
- `.deb`：`fakeroot`、`dpkg-deb`
