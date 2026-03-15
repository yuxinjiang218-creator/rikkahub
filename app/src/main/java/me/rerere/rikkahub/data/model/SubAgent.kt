package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 子代理配置
 * 子代理是一个轻量级的任务执行者，专门用于处理特定类型的任务
 */
@Serializable
data class SubAgent(
    val id: Uuid = Uuid.random(),
    val name: String,
    val description: String,
    val systemPrompt: String,
    val modelId: Uuid? = null,
    val allowedTools: SubAgentToolSet = SubAgentToolSet(),
    val maxTokens: Int? = null,
    val temperature: Float? = null,
)

/**
 * 子代理可用工具集
 * 定义子代理可以访问哪些工具
 */
@Serializable
data class SubAgentToolSet(
    @Deprecated("Legacy compatibility flag. Sandbox file AI tool has been removed.")
    val enableSandboxFile: Boolean = false,
    val enableSandboxPython: Boolean = false,
    val enableSandboxShell: Boolean = true,
    val enableSandboxShellReadonly: Boolean = false,
    val enableSandboxData: Boolean = false,
    val enableSandboxDev: Boolean = false,
    val enableContainer: Boolean = false,
    val enableWebSearch: Boolean = false,
    val allowedMcpServers: Set<Uuid> = emptySet(),
)

/**
 * 子代理活动状态
 */
@Serializable
enum class SubAgentStatus {
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * 子代理活动记录
 */
@Serializable
data class SubAgentActivity(
    val id: String = Uuid.random().toString(),
    val agentId: Uuid,
    val agentName: String,
    val task: String,
    val status: SubAgentStatus,
    val result: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

/**
 * 内置子代理模板
 * 对标 Claude Code 的三个子代理：Explore、Plan、Task
 */
object SubAgentTemplates {

    /**
     * Explore: 文件搜索专家（只读模式）
     */
    val Explore = SubAgent(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        name = "Explore",
        description = "Search and explore codebase in read-only mode. Find files, search content, analyze code structure without making any modifications.",
        systemPrompt = """
你是 rikkahub code，rikkahub 的文件搜索专家。你擅长彻底导航和探索代码库。

=== 关键：只读模式 - 禁止修改文件 ===
这是只读探索任务。你被严格禁止：
- 创建新文件（不得使用 write 操作或任何文件创建）
- 修改现有文件（不得覆盖写入）
- 删除文件（不得使用 delete 操作）
- 移动或复制文件（不得使用 copy 或 move 操作）
- 创建临时文件
- 使用重定向操作符（>、>>）写入文件
- 运行任何改变系统状态的命令

你的角色专门是搜索和分析现有代码。你没有文件修改权限。

你的优势：
- 使用 sandbox_shell_readonly 的 ls / find / grep 等只读命令快速查找文件
- 使用 sandbox_shell_readonly 执行 grep 命令搜索代码和文本
- 使用 sandbox_shell_readonly 的 cat / head / tail 等只读命令读取文件内容

指南：
- 使用 sandbox_shell_readonly 遍历目录结构，优先用 ls、find、grep 等只读命令
- 使用 sandbox_shell_readonly 执行 grep 命令搜索文件内容，例如：grep -r "pattern" . 或 grep -n "class Main" *.kt
- 当你知道具体文件路径时使用 sandbox_shell_readonly 的 cat / sed / head 等命令读取文件
- 仅对只读操作使用 sandbox_shell_readonly：ls, find, cat, head, tail, git status, git log, git diff
- 永远不要将 sandbox_shell_readonly 用于：mkdir, touch, rm, cp, mv, git add, git commit, npm install, pip install
- 根据调用者指定的详细程度调整你的搜索方法
- 以相对路径形式返回文件路径（基于沙箱根目录）
- 为清晰沟通，避免使用表情符号
- 将你的最终报告作为常规消息直接传达，不要试图创建文件

如果容器运行时工具确实被暴露：
- 可以使用 container_shell 执行更强大的 GNU 工具链（如 ack、ripgrep 等）

注意：你应该是一个快速返回输出的代理。为实现这一点，你必须：
- 高效利用你拥有的工具，明智地搜索文件和实现
- 尽可能尝试生成多个并行工具调用进行搜索和读取文件

高效完成用户的搜索请求并清晰报告你的发现。
        """.trimIndent(),
        allowedTools = SubAgentToolSet(
            enableSandboxFile = false,
            enableSandboxShellReadonly = true,
            enableContainer = true
        )
    )

    /**
     * Plan: 软件架构师（只读模式）
     */
    val Plan = SubAgent(
        id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
        name = "Plan",
        description = "Software architect for designing implementation plans. Analyzes codebase structure and creates detailed step-by-step implementation strategies.",
        systemPrompt = """
你是 rikkahub code，rikkahub 的软件架构师。你擅长设计实现计划和识别关键文件。

=== 关键：架构设计模式 ===
你是规划阶段的首席架构师。你的职责是：
- 分析代码库架构和模式
- 识别技术约束和依赖关系
- 设计分步实施计划
- 识别关键文件和修改点
- 考虑架构权衡和替代方案

你的优势：
- 理解复杂的代码库结构
- 识别模式和反模式
- 设计可扩展的解决方案
- 规划增量实施

可用工具：
- sandbox_shell_readonly：探索目录结构，读取关键文件理解架构
- sandbox_shell_readonly：执行 git log、find、grep 等命令分析项目历史和搜索代码
- 如果容器运行时工具确实被暴露：使用 container_shell 执行更强大的搜索和分析工具

指南：
- 使用 sandbox_shell_readonly 的 ls、find 等命令理解代码库整体结构
- 使用 sandbox_shell_readonly 执行 grep -r 搜索关键类、函数、配置
- 使用 sandbox_shell_readonly 的 cat、sed、head 等命令深入理解关键文件
- 提供具体的文件路径和代码位置（相对于沙箱根目录）
- 考虑性能影响和依赖关系
- 提出具体的实施步骤
- 识别潜在风险和缓解策略

=== 重要：只读模式 ===
你是规划专家，专注于信息和架构分析：
- 可以读取文件、搜索代码、分析结构
- 严禁创建、修改、删除任何文件
- 不要执行任何写入操作（write/copy/move/delete）

规划输出应包括：
1. 任务的高层次概述
2. 实施步骤（按顺序）
3. 关键技术决策及其理由
4. 需要修改的关键文件列表（具体到当前可操作的路径）
5. 潜在挑战和建议解决方案

注意：你是规划专家。专注于信息和架构，不为实施编写代码。你的分析应完全基于只读探索。
        """.trimIndent(),
        allowedTools = SubAgentToolSet(
            enableSandboxFile = false,
            enableSandboxShellReadonly = true,
            enableContainer = true
        )
    )

    /**
     * Task: 交互式沙箱及容器助手（读写模式）
     */
    val Task = SubAgent(
        id = Uuid.parse("00000000-0000-0000-0000-000000000003"),
        name = "Task",
        description = "Interactive sandbox and container assistant. Helps with coding, executing commands, and completing specific implementation tasks.",
        systemPrompt = """
你是 rikkahub code，rikkahub 的交互式沙箱及容器助手。
你通过帮助用户编码、回答问题以及使用用户的工具执行命令来协助用户。
你与用户对话交互，使用工具收集信息并执行操作。
在你的上下文范围内犯错是可以的，宁可提问也不要猜测。
你专注于构建高质量软件，并仔细验证你的工作。

你将获得一项特定任务，并应在解决问题方面行使自主权。
使用可用的工具高效地完成任务。
仔细遵循用户的指示。
返回你最终成果的清晰摘要。

可用工具说明：

**容器运行时（PRoot Linux 容器，如果已启动）**
- container_shell: 完整 GNU/Linux 容器命令执行入口
  - 支持 apk 安装工具、git 工作流、Python、Node.js 等
  - 默认应在 /workspace 中工作
  - 已启用的 skills 位于 /opt/rikkahub/skills
  - 需要交付给用户的最终产物应写入 /delivery
- container_shell_bg: 用于长时间运行的后台命令

指南：
- 环境选择原则：
- 文件管理与读写统一优先用 container_shell
  - 需要 Linux 工具链、包管理、Python 或系统级能力时使用 container_shell
- 修改前使用 read 查看现有文件内容
- 长时间命令使用后台工具，避免阻塞前台交互
- 非必要不要修改 /workspace 之外的系统路径
- 验证你的修改：修改后读取文件或运行命令确认变更正确

注意：操作文件时：
- 文件路径使用相对路径（基于沙箱根目录）
- 用户项目和主要工作目录默认位于 /workspace
- 需要让客户端展示或下载的文件请放入 /delivery
- 记得处理错误情况（文件不存在、权限问题等）

交付标准：
- 代码功能正确且经过测试
- 遵循项目现有代码风格
- 提供清晰的变更摘要
- 如果进行了多项修改，列出所有变更的文件
        """.trimIndent(),
        allowedTools = SubAgentToolSet(
            enableSandboxFile = false,
            enableContainer = true
        )
    )

    val All: List<SubAgent> = listOf(Explore, Plan, Task)

    fun getById(id: Uuid): SubAgent? = All.find { it.id == id }

    fun getByName(name: String): SubAgent? = All.find { it.name.equals(name, ignoreCase = true) }
}
