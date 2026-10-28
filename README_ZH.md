<h1 align="center">饭听 · FanTing
</h1>

<p align="center">
  简体中文 · <a href="README.md">English</a>
</p>

<p align="center">
  开源 Android 网络电台与播客播放器 · 打开听，随便听
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/License-AGPL--3.0-blue" alt="AGPL-3.0" />
  <img src="https://img.shields.io/badge/Version-1.0.0-orange" alt="1.0.0" />
</p>

<p align="center">
  <img src="media/icon.webp" alt="饭听" width="50%" />
</p>

<p align="center">
  <b>Release 安装包约 3.2 MB</b> &nbsp;·&nbsp; <b>Android 8.0 起可用</b> &nbsp;·&nbsp; <b>无广告 / 免登录</b>
</p>

饭听是一个把**网络电台**和**播客**放进同一个应用的开源播放器。你可以手动维护自己的电台库，也可以在庞大的公共电台目录里搜索、试听并收藏；同时用 RSS、搜索或热门榜单订阅播客，断点续播、倍速、批量下载一应俱全。

Release 安装包约 **3.2 MB**，Android 8.0（API 26）及以上即可运行，老机型、存储紧张的设备也能装得下。没有广告，没有追踪，没有分析统计，也不需要注册账号。所有电台、订阅和播放进度都保存在你自己的设备上。

## 功能一览

### 电台

- **两种添加方式。** 手动填写标题、流地址、封面和标签；或在线搜索后一键加入。
- **在线电台检索。** 基于 [Radio Browser](https://www.radio-browser.info) 公共目录，支持按标题、标签检索，并按国家、语言、码率区间筛选，可按点击量、投票、名称、码率、国家或随机排序，正序倒序皆可。添加前可先试听，确认能播再入库。
- **电台库管理。** 收藏、标签、封面显示开关，支持按添加日期、名称、收藏状态排序，也可以直接拖拽排出完全自定义的顺序。
- **播放面板。** 显示封面、实时曲目元数据（ICY）、码率、采样率和音频格式；点按信息区复制当前曲名，长按查看完整音频流详情。支持随机、上一台 / 下一台切换。
- **自动补全封面。** 设置里可让应用联网为所有电台批量查找封面，找不到时会明确提示。
- **播放行为可调。** 暂停后多久停止后台服务（或永不停止）、被其它应用打断结束后是否自动续播、停止后是否保留快捷设置里的媒体控件、开始播放时是否自动展开面板、长标题是否滚动、是否显示码率。

### 播客

- **订阅方式齐全。** 手动粘贴 RSS 地址、按名称或作者搜索 iTunes 目录、浏览热门榜单，搜索地区可选美、中、台、港、日、韩、英、德。
- **收听体验。** 单集断点续播并自动保存进度，0.5× / 0.75× / 1× / 1.25× / 1.5× / 2× / 3× 倍速，后退 15 秒、前进 30 秒，单集播完可自动接着播下一集。
- **下载管理。** 单集下载、批量下载、移动数据下载前询问（可设为仅 Wi-Fi 下载）、下载进度与占用空间统计、导出音频文件、一键清空全部下载。
- **节目管理。** 收藏单集、标记已听 / 未听、按全部 / 未听 / 进行中 / 已完成筛选、单集搜索、长按滑选批量操作、按最新 / 最早 / 最后更新 / 标题排序、标准 / 紧凑 / 纯封面三种列表布局。
- **自动刷新。** 可设为启动时刷新、进入播客页刷新，以及 10 分钟 / 1 小时 / 6 小时 / 24 小时的最小刷新间隔；手动刷新不受限制。

### 通用

- **超轻量。** Release 安装包约 3.2 MB，没有内置浏览器内核，也没有广告、统计一类第三方 SDK，权限申请只保留播放与联网所必需的几项。
- **系统的播放控制。** 通知栏、锁屏、快捷设置和系统媒体面板都能控制播放，甚至可以在应用未运行时从快捷设置直接续播上次的电台。
- **Android Auto。** 车载界面提供全部电台、收藏、标签和最近播放四个分类，并遵循你在应用里保存的列表顺序。
- **数据可携带。** JSON 完整备份（电台、排序、播客订阅、单集进度、播放历史）与 TXT 简易格式（仅电台，可手改），导入时支持**替换**或**合并**。
- **外观。** 浅色 / 深色 / 跟随系统三种主题，六套强调色（紫罗兰、海蓝、藏青、琥珀、玫瑰、太极）。
- **界面语言。** 简体中文、繁体中文、英语、俄语，可在应用内切换，默认跟随系统。
- **平板适配。** 手机、7 寸与 10 寸平板均有对应布局。
- **应用内更新。** GitHub 构建版本可从「关于」页检查并下载新版本 APK。

## 支持格式

- 直接流地址（HTTP / HTTPS）
- PLS
- M3U
- HLS / M3U8
- XSPF
- ASX / WAX / WMX
- DASH / MPD

## 截图

| 电台列表 | 播客列表 | 
| :---: | :---: |
| ![电台列表](media/1.webp) | ![播客列表](media/2.webp) |
| **播客发现** | **设置** |
| ![播客发现](media/3.webp) | ![设置](media/4.webp) |

## 安装

从 [Releases](https://github.com/ruzhe85/FanTing/releases) 下载最新 APK，大小约 3.2 MB，直接安装即可。

- 系统要求：Android 8.0（API 26）及以上
- 应用 ID：`app.ruzhe.fanting`，可与上游官方版 OmniBeatP 共存安装
- 更新安装需要与已安装版本使用相同的签名

## 备份格式

**JSON** 是应用的原生备份格式，保存电台、排序状态、播客订阅与单集进度、播放历史，适合换机迁移和长期存档。

**TXT** 只保存电台，每三行为一组：标题、流地址、可选标签（逗号分隔），可以直接用文本编辑器维护。

```txt
Nightride FM
https://stream.nightride.fm/nightride.mp3
synthwave, electronic

Classic Vinyl HD
https://icecast.walmradio.com:8443/classic
mp3, 320 kbps
```

导入时可以选择「替换」清空现有数据，或「合并」保留现有数据并按流地址更新重复电台。

## 隐私

- 不收集任何统计、埋点或崩溃上报
- 不需要账号，不上传电台库与播客订阅
- 联网只用于：在线电台搜索（Radio Browser）、播客搜索与榜单（iTunes）、抓取封面与 RSS、GitHub 版本的更新检查


## 致谢

饭听基于 [OmniBeatP](https://github.com/Vikindor/omnibeat)（作者 Vikindor）继续开发，感谢原作者与以下开源项目：

- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media)：音频播放、媒体会话与系统播放控制
- [Reorderable](https://github.com/Calvin-LL/Reorderable)：Compose 中的拖拽排序
- [Radio Browser](https://www.radio-browser.info)：在线电台目录与电台元数据
- [iTunes Search API](https://performance-partners.apple.com/search-api)：播客搜索与榜单数据

## 许可证

[GNU Affero General Public License v3.0](LICENSE)
