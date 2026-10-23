# AJ Mini —— 简易版私人助手 App

一个装在自己手机上的、像 Minis 一样的助手。三个页签：**聊天 / 终端 / 设置**。

- 内置 Alpine Linux（PRoot，无需 root、不依赖 Termux）
- 聊天页跟 AI 说话，它能自己执行命令干活
- 终端页是你自己的 shell，跟聊天共用同一个 Linux
- 钩子、技能、记忆都是普通文件，你想加什么改什么

## 你要做的三步

### 1. 建仓库

GitHub → New repository → 公开 → **不要**勾 README / .gitignore / license。

### 2. 推上去

```sh
cd miniapp
git init
git add .
git commit -m init
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

### 3. 下 APK

仓库页 → Actions → 最新一次 `Build APK` → 页面底部 Artifacts → `aj-mini-debug.zip` → 解压得 `app-debug.apk` → 装。

## 装上之后

1. 打开 App，首次会自动解压 Linux，等几十秒
2. 去 **设置** 页，填 API Key（DeepSeek 的，接口地址和模型名已预填好）
3. 回 **聊天** 页直接说话；或去 **终端** 页自己敲命令

验证环境正常：设置页点「检查环境」，应该看到 Alpine 版本号。

## 文件都在哪

App 私有目录，路径类似 `/data/data/dev.aj.miniapp/files/`：

```
files/
├── rootfs/          # 内置的 Alpine，终端里看到的 /
├── hooks/
│   ├── before.sh    # 每条命令执行前，返回非 0 就拦截
│   └── after.sh     # 每条命令执行后
├── skills/          # 技能目录，放 <name>/SKILL.md
└── memory/
    └── GLOBAL.md    # 全局记忆
```

在终端里它们挂在 `/host`，也就是：

```sh
cat /host/hooks/before.sh
vi /host/memory/GLOBAL.md
```

## 加钩子示例

禁止 AI 删东西 —— 把 `/host/hooks/before.sh` 改成：

```sh
#!/bin/sh
case "$1" in
  *"rm -rf"*) echo "不许删"; exit 1 ;;
esac
exit 0
```

拦截后模型会收到「不许删」，自己换办法。

## 这一版没有的

- 流式输出（等模型说完才显示）
- 会话历史落盘（退出即清）
- 多 provider 切换（DeepSeek 的 OpenAI 兼容格式，改接口地址也能接别家）
- 技能自动注入（目录建好了，读取逻辑下一版加）

## 技术说明

| 组件 | 来源 |
|---|---|
| proot | proot-me 官方 v5.3.0 aarch64 静态构建 |
| Linux | Alpine 3.20.3 minirootfs |
| proot 放哪 | `jniLibs/arm64-v8a/libproot.so`（改名绕开私有目录不可执行的限制，`useLegacyPackaging=true` 保证被解压成可执行文件） |
| rootfs 放哪 | APK assets，首次启动解压到 `filesDir/rootfs` |
