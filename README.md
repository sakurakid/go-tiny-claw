# go-tiny-claw

一个用于练习 Agent Harness 思路的 Java 简易 Demo。

这个仓库不是一个成熟的生产级 Agent 框架，而是我用来拆解、复现和练习现代 Coding Agent 底层运行时设计的小项目。项目参考了原 Go 版本思路，但实现语言切换为 Java，重点关注 Harness 这一层：如何给大模型提供一个足够小、足够稳、边界清晰的运行环境。

## 为什么做这个练习

传统 Agent Framework 往往会把业务流程写成 Chain、DAG 或复杂的隐式状态机。早期模型推理能力弱时，这种方式可以帮助模型完成意图识别和路由分发；但当前模型已经具备更强的规划能力和工具调用能力，过厚的框架层反而容易带来三类摩擦：

- 上下文失控：工具描述和系统设定过多，稀释模型注意力，增加幻觉和错误调用概率。
- 状态失控：框架内部维护复杂隐式状态，长对话后容易遗忘、死循环，也不方便人工中途介入。
- 边界失控：Agent 能执行命令、写文件、改环境，但缺少底层拦截和审批机制时风险很高。

因此这个项目把练习重点放在 Harness 上。我的理解是：Harness 更像是给大模型写一个微型操作系统。模型负责推理和规划，Harness 负责提供上下文、调度工具、保存状态、压缩记忆、拦截危险动作，并在必要时把控制权交还给人。

## 项目目标

- 用 Java 实现一个极简 Agent Harness 骨架。
- 不用厚重 DAG 描述业务流程，而是围绕 Main Loop 组织 ReAct 循环。
- 保持工具集极简，让模型通过 `read / write / edit / bash` 组合能力。
- 用 Middleware 拦截高危命令，预留 Human-in-the-loop 审批入口。
- 用文件系统保存计划、TODO 和运行状态，避免把关键状态藏在框架黑盒里。
- 作为个人练习项目，逐步验证上下文管理、工具注册、模型适配和状态外置等设计。

## 分层设计

```text
go-tiny-claw/
├── src/main/java/lab/agentharness/
│   ├── claw/          # CLI 入口
│   ├── engine/        # Main Loop / ReAct 核心循环
│   ├── provider/      # 大模型 Provider 抽象与适配
│   ├── context/       # Prompt 动态组装、Token 监控、上下文压缩
│   ├── tools/         # Tool Registry、基础工具、Middleware
│   ├── memory/        # 基于文件系统的 PLAN / TODO / 运行记忆
│   ├── feishu/        # 飞书 AgentOps / 审批回调预留
│   └── thinking/      # 行动前慢思考模块
├── src/test/java/     # 单元测试
└── docs/              # 设计笔记
```

## 当前阶段

当前仓库处于初始化阶段，主要用于沉淀项目说明和目录骨架。后续会按下面顺序补充代码：

1. CLI 启动入口。
2. Provider 接口与 Mock Provider。
3. Tool Registry 和内置文件工具。
4. Bash 工具及危险命令 Middleware。
5. 文件系统 Memory。
6. Main Loop 贯穿一次最小 ReAct 流程。
7. 飞书审批和异步回调占位实现。

## 设计原则

- 少即是多：Harness 不教模型怎么想，只提供稳定的物理环境。
- 状态外置：关键进度尽量写入本地文件，让人类随时可读、可改、可恢复。
- 默认保守：写文件、执行命令、删除内容等动作必须经过边界检查。
- 可插拔：Provider、Tool、Middleware 都要能独立替换。
- 先跑通 Demo：先完成最小闭环，再逐步加强工程能力。

## 运行计划

项目后续会使用 Maven 管理：

```bash
mvn test
mvn package
java -jar target/agent-harness-java-lab.jar
```

目前 README 先记录练习方向，代码实现会在后续提交中逐步补齐。

## 备注

仓库名保留为 `go-tiny-claw`，是因为最初参考材料来自 Go 版本的 Tiny Claw / Agent Harness 练习思路；本仓库实际实现会以 Java 为主。
