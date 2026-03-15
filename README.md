# RikkaHub Fork

这是一个基于上游 RikkaHub 持续维护的 Android LLM 客户端分支，默认展示的是 `master`。

## `master` 比 `main` 多出的具体功能

### 1. 本地工具能力更完整

- 新增沙箱文件工具，AI 可以在隔离沙箱里读写、列出、复制和删除文件
- 新增 ChaquoPy Python 工具，支持沙箱内 Python 执行、数据处理和绘图
- 新增 PRoot 容器工具，容器运行后可直接执行 shell、后台进程和容器内 Python
- 新增 Workflow TODO 工具，可在对话里创建、读取、更新待办项
- 新增 Workflow Control，可在聊天页直接开关工作流能力
- 新增 SubAgent 工具，可把任务拆给子代理并行处理
- 助手设置页额外提供“文件管理”界面，可以直接查看并管理该助手各个对话沙箱里的文件

### 2. 聊天与上下文管理更强

- 压缩上下文从“重写会话”改成“维护滚动摘要”，原始消息时间线会保留
- 支持自动压缩，可按 token 阈值自动触发
- 支持重新生成最近一次压缩摘要
- 支持为当前会话重建记忆索引
- 新增 Indexed History Recall，可检索结构化历史记忆，再定位并读取原始消息
- 对话分叉时会复制对应沙箱文件，避免新分支丢失运行上下文

### 3. 工作流交互

- 聊天页增加 Workflow 侧边句柄和浮动面板
- 可以在对话中切换工作流阶段，而不只是单纯聊天
- Workflow 状态、TODO 状态会跟着会话一起保存

### 4. 提供商与搜索配置更适合长期使用

- OpenAI、Claude、Google 的 API Key 输入改成多 Key 池编辑器
- Tavily 也支持多 Key 池配置
- Key 轮询带持久化游标，重启应用后仍能继续轮换，不是每次随机抽 key
- 搜索工具执行失败时会返回结构化失败信息，工具输出也会做长度截断，减少异常把上下文撑爆的情况

### 5. 更新与发布链路已改成 fork 自己的渠道

- 更新检查改为读取这个 fork 的 GitHub Releases
- 版本号解析和 APK 下载链接都跟随当前仓库发布，而不是上游默认更新源

## 构建

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

如需构建完整应用，请在 `app/` 下提供 `google-services.json`。

## 致谢

感谢 RikkaHub 上游项目持续迭代，这个仓库的维护建立在上游工作的基础之上。
