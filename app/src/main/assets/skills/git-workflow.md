---
name: git-workflow
description: Git 协作流程技能。用户要提交代码、建分支、解决冲突、整理提交历史或回退改动时使用。
---

# Git 工作流技能

## 提交前
1. git status 与 git diff 确认改动范围，只暂存相关文件。
2. 检查没有密钥、日志、构建产物。
3. 提交信息用“类型: 摘要”，类型取 feat/fix/refactor/chore/docs。

## 分支
- 新功能与修复各开独立分支，分支名带日期与主题。
- 合并前先同步主干并解决冲突。

## 回退
- 优先 git revert 生成反向提交，保留历史。
- 破坏性命令（reset --hard、push --force、clean -f）先向用户说明影响并获得确认。
