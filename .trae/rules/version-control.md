---
description: 版本管理规范，保证开发可溯源与可持续
alwaysApply: true
---

# 版本管理（可溯源 + 可持续）

本项目的所有开发都必须纳入 Git 管理。每次任务遵循以下规范。

## 仓库

- 项目根目录即唯一 Git 仓库；远程 `origin` 指向 `https://github.com/Horizon-fyz/qscan-ai`。
- 忽略规则以根目录 `.gitignore` 为准：不提交构建产物、APK、密钥与本机配置；`awesome-design-md/` 仅本地参考、不纳入版本库；Gradle Wrapper（`gradlew`、`gradle/wrapper/`）必须保留提交。

## 提交粒度与信息

- 一个逻辑变更一次提交；不把无关改动混在一起；禁止 `update` / `fix` / `111` 之类无信息量的信息。
- 信息格式：`<type>(<scope>): <简述>`，type 取 `feat` / `fix` / `refactor` / `perf` / `docs` / `test` / `chore` / `build`。
  - 例：`feat(float-panel): 新增截图取词入口`
  - 例：`fix(ocr): 修复竖排文本识别乱序`
- 需要时在正文说明「为什么」、影响范围与验证方式。

## 可溯源

- 每个可交付节点都要落一次提交，保证任意历史版本可 `checkout` 复现。
- 涉及接口 / 数据 / UI 约定的决策必须留痕（提交信息或 `CHANGELOG`）：改了什么、为什么、影响谁。
- 破坏性变更在信息中显式标注 `BREAKING CHANGE`。

## 可持续

- 动手前先 `git status` / `git log` 确认当前基线与未提交改动，避免覆盖既有成果。
- 推送前先同步远程（`git pull --rebase`），再推送；不向主干 force push。
- 阶段性完成后明确告知用户：本次提交了什么、如何回退（`git revert <sha>` 或 `git reset`）。

## 禁止

- 不提交密钥、token、`local.properties` 等敏感或本机专有文件。
- 不使用 `--force` / `--force-with-lease` 覆盖远程历史（用户明确要求时除外）。
