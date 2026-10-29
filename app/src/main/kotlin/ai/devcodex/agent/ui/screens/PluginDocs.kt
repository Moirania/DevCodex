package ai.devcodex.agent.ui.screens

/** 插件开发与使用文档（Markdown）。内容依据 PluginRepo、PluginExecutor 的实际行为编写。 */
val PLUGIN_DOC_MD: String = """
# 插件开发与使用指南

## 插件是什么

插件是一个 JSON 文件，声明一个或多个工具。AI 判断何时调用工具并填写参数，DevCodex 按你写的模板发送 HTTP 请求，再把响应交还给 AI。

适合接入：天气查询、Home Assistant、n8n 工作流、自建 API、内部系统。

## 插件与技能的区别

| 类型 | 作用 | 格式 |
| --- | --- | --- |
| 插件 | 给 AI 增加一只手：调用外部 HTTP 接口 | JSON |
| 技能 | 给 AI 增加一份知识：方法与流程说明 | SKILL.md |

两者可以配合：技能说明做事的方法，插件提供调用的工具。

## 快速开始

复制下面的 JSON，在插件页点右下角按钮，选择粘贴安装即可。

```json
{
  "id": "weather-demo",
  "name": "天气查询",
  "version": "1.0.0",
  "author": "你的名字",
  "description": "查询城市天气",
  "tools": [
    {
      "name": "get_weather",
      "description": "用户询问某城市的天气时调用",
      "parameters": "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市英文名，如 Beijing\"}},\"required\":[\"city\"]}",
      "endpoint": {
        "method": "GET",
        "url": "https://wttr.in/{{city}}?format=3"
      }
    }
  ]
}
```

安装后在对话里说「查一下 Beijing 的天气」，AI 会自动调用 get_weather。

## 字段说明

### 插件层

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| id | 是 | 唯一标识，同 id 再次安装会覆盖旧版本 |
| name | 是 | 显示名称 |
| version | 否 | 版本号，默认 1.0.0 |
| author | 否 | 作者 |
| description | 否 | 插件说明 |
| enabled | 否 | 是否启用，默认 true |
| tools | 是 | 工具列表，至少 1 个 |

### 工具层（tools 的每一项）

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| name | 是 | 工具名，英文，不能含空格 |
| description | 否 | 写清楚何时该用，AI 靠它决定是否调用 |
| parameters | 否 | JSON Schema 的字符串形式，默认 {} |
| endpoint | 是 | HTTP 请求配置 |

### 端点层（endpoint）

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| url | 是 | 请求地址，支持 {{参数名}} |
| method | 否 | GET、POST、PUT、DELETE，默认 POST |
| headers | 否 | 请求头，值里支持 {{参数名}} |
| bodyTemplate | 否 | 请求体模板，用于 POST 与 PUT |
| resultPath | 否 | 从 JSON 响应里提取结果的路径 |

## 参数写法

parameters 是一个字符串，里面放 JSON Schema，内部的双引号要写成 `\"` 转义：

```json
"{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市名\"}},\"required\":[\"city\"]}"
```

每个参数都写 description，AI 依靠它填对值。parameters 解析失败时，工具会退化成没有参数。

## 模板规则

- {{参数名}} 会被 AI 填写的参数值原样替换，不做 URL 编码。
- URL 里的空格会自动转成 %20，其他特殊字符需要保证参数值本身合法。
- 参数名与 {{}} 里的名字必须完全一致，未匹配的占位符会原样保留。
- GET 与 DELETE 不发送请求体，参数只能通过 URL 与 headers 里的占位符传递，没有写占位符的参数会被丢弃。
- 其他方法（POST、PUT、PATCH 等）没写 bodyTemplate 时，请求体是 AI 填写的参数 JSON；写了就按模板发送。
- 请求体的 Content-Type 固定为 application/json。

带请求头和请求体的例子：

```json
"endpoint": {
  "method": "POST",
  "url": "https://api.example.com/v1/notes",
  "headers": { "Authorization": "Bearer YOUR_KEY" },
  "bodyTemplate": "{\"title\": \"{{title}}\", \"text\": \"{{text}}\"}"
}
```

## 结果提取 resultPath

- 留空：返回完整响应，最多保留前 8000 个字符。
- 填 data.items：按点号逐层读取 JSON 对象的字段。
- 只支持对象字段，不支持数组下标。
- 路径中途不存在或响应不是 JSON 时，返回完整响应（同样最多 8000 个字符）。
- 命中的值以 JSON 文本返回：字符串字段会带双引号，对象与数组返回其 JSON 内容，结果同样最多 8000 个字符。
- 请求失败时，AI 收到 HTTP 状态码加响应前 500 个字符，或以 ERROR 开头的异常信息。

## 安装与校验

支持三种方式：从文件安装、输入 URL 下载、粘贴 JSON。安装时会检查：

1. id 与 name 不能为空。
2. 至少声明一个工具。
3. 每个工具都要有 name 与 endpoint.url。
4. 工具名不能含空格。

同一插件内的工具名请自行避免重复，不同插件之间的工具名也不要冲突，否则 AI 无法区分。

## 调试流程

1. 保存 JSON 后安装，确认插件页出现该插件且开关已打开。
2. 在对话里明确说出要做的事，例如「用 get_weather 查 Beijing 的天气」。
3. 展开工具卡片，检查 AI 填写的参数与返回内容。
4. 返回 HTTP 4xx，检查 headers 与参数；返回 ERROR，检查 URL 与网络。
5. 修改 JSON 后用相同 id 重新安装，即可覆盖。

## 编写建议

1. description 写清楚何时使用，不要只写接口是什么。
2. API Key 放在 headers 里，不要拼进 URL。
3. 响应很大时设置 resultPath，减少 AI 读取的内容。
4. 一个工具只做一件事，参数尽量少。
5. 插件 JSON 以明文保存在应用私有目录，不要分享带真实密钥的文件。

## 技能（SKILL.md）格式

技能是 Markdown 文件，开头用 frontmatter 写名称与简介：

```markdown
---
name: translate
description: 中英互译，用户要求翻译时使用
---
将用户内容翻译成中文，若已是中文则译成英文，只输出译文。
```

- name 必填，description 建议填写。
- description 常驻系统提示词，正文由 AI 在需要时通过 skill_read 读取。
- 在设置页的技能入口安装，也可以从 GitHub 仓库导入。
""".trim()
