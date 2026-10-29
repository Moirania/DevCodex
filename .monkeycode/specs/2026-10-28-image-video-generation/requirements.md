# Requirements Document

## Introduction

为 DevCodex 增加生图模型与视频模型的调用能力。聊天主模型保持不变，Agent 通过工具调用生图与视频接口，结果保存到工作区并在聊天界面展示。第一版以 OpenAI 兼容的 images/generations 接口为主，视频采用提交、轮询、下载的通用异步任务格式，并提供自定义端点以适配智谱 CogView、通义万相、豆包 Seedream 等厂商。聊天输入同时增加图片附件，使识图能力可用。

## Glossary

- **Agent**: DevCodex 中驱动对话与工具调用的执行引擎。
- **提供商**: 一份 ProviderConfig，包含类型、baseUrl、apiKey 与模型列表。
- **生图模型**: 通过图片生成接口返回图片的模型，例如 gpt-image-1、cogview、wanx、seedream。
- **视频模型**: 通过异步任务接口返回视频的模型，例如 cogvideox、seedance。
- **工作区**: BuiltinTools.workspaceRoot() 返回的目录，用于保存生成的文件。
- **自定义端点**: 提供商设置中由用户填写的提交地址、查询地址与字段路径。
- **附件**: 用户在聊天输入中选择并随消息发送的图片文件。

## Requirements

### Requirement 1

**User Story:** AS 用户, I want 为每个提供商指定生图模型与视频模型, so that Agent 知道调用哪个模型生成媒体。

#### Acceptance Criteria

1. WHEN 用户在提供商设置页保存配置, the 提供商设置页 SHALL 保存生图模型 ID、视频模型 ID 与自定义端点。
2. WHEN 模型 ID 包含 image、cogview、wanx、seedream 或 dall-e 关键词, the ModelRegistry SHALL 将该模型的类型标记为 image。
3. WHEN 模型 ID 包含 video、cogvideo、seedance 或 veo 关键词, the ModelRegistry SHALL 将该模型的类型标记为 video。
4. WHILE 模型类型为 image 或 video, the Agent SHALL 将该模型从聊天主模型候选列表中排除。

### Requirement 2

**User Story:** AS 用户, I want 让 Agent 根据文字描述生成图片, so that 我在对话中直接得到图像结果。

#### Acceptance Criteria

1. WHEN Agent 调用 image_generate 工具, the 工具 SHALL 向 baseUrl 加 /v1/images/generations 发送包含 model、prompt、size 与 n 的 JSON 请求。
2. WHEN 接口返回图片 URL, the 工具 SHALL 下载图片并保存到工作区的 generated 目录。
3. WHEN 接口返回 base64 数据, the 工具 SHALL 解码并保存为 PNG 文件到工作区的 generated 目录。
4. WHEN 图片保存成功, the 工具 SHALL 返回保存路径与图片数量。
5. IF 提供商未配置生图模型, the 工具 SHALL 返回说明缺少生图模型配置的错误文本。

### Requirement 3

**User Story:** AS 用户, I want 让 Agent 生成视频并获取结果, so that 我无需手动轮询厂商接口。

#### Acceptance Criteria

1. WHEN Agent 调用 video_generate 工具, the 工具 SHALL 向提交地址发送任务请求并返回任务 ID。
2. WHEN Agent 调用 video_status 工具并传入任务 ID, the 工具 SHALL 向查询地址请求任务状态并返回 pending、running、succeeded 或 failed。
3. WHEN 任务状态为 succeeded, the 工具 SHALL 下载视频并保存到工作区的 generated 目录。
4. IF 任务状态为 failed, the 工具 SHALL 返回厂商给出的失败原因。
5. IF 轮询累计时间超过 10 分钟, the 工具 SHALL 返回超时错误并保留任务 ID。

### Requirement 4

**User Story:** AS 用户, I want 在聊天里看到生成的图片与视频, so that 我无需打开文件管理器查找结果。

#### Acceptance Criteria

1. WHEN 工具结果包含工作区内的图片路径, the 聊天界面 SHALL 在工具卡片下方显示图片缩略图。
2. WHEN 用户点击缩略图, the 聊天界面 SHALL 以全屏方式显示原图。
3. WHEN 工具结果包含工作区内的视频路径, the 聊天界面 SHALL 显示视频封面与播放按钮。
4. WHEN 用户点击播放按钮, the 聊天界面 SHALL 调用系统播放器打开该视频。

### Requirement 5

**User Story:** AS 用户, I want 在消息中附带图片, so that 模型可以识别图片内容。

#### Acceptance Criteria

1. WHEN 用户点击输入框的附件按钮, the 聊天界面 SHALL 打开系统图片选择器。
2. WHEN 用户选择图片, the 聊天界面 SHALL 在输入框上方显示缩略图与移除按钮。
3. WHEN 用户发送带附件的消息且提供商类型为 OPENAI_COMPAT, the 提供商层 SHALL 以 image_url 内容块携带 base64 数据。
4. WHEN 用户发送带附件的消息且提供商类型为 ANTHROPIC, the 提供商层 SHALL 以 image 内容块携带 base64 数据。
5. IF 当前模型的 vision 标记为 false, the 聊天界面 SHALL 提示当前模型不支持图片输入。

### Requirement 6

**User Story:** AS 用户, I want 对媒体生成工具有明确的权限与费用提示, so that 我掌控 API 调用产生的费用。

#### Acceptance Criteria

1. WHEN Agent 调用 image_generate 或 video_generate, the 权限层 SHALL 按 MEDIUM 风险级别处理该调用。
2. WHEN 全局权限为 ASK, the 权限层 SHALL 在执行前弹出审批卡片并显示提示词摘要。
3. IF 接口返回 HTTP 4xx 或 5xx, the 工具 SHALL 返回状态码与响应前 500 个字符。
4. IF 网络请求抛出异常, the 工具 SHALL 返回以 ERROR 开头的异常信息。
5. THE 工具 SHALL 在日志与工具结果中隐藏 apiKey 内容。
