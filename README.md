# RuoYi-Vue-Plus Module Fast Generator

![Build](https://github.com/korykim/ruoyi-vue-plus-module-fast-generator/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
## Introduction

RuoYi-Vue-Plus Module Fast Generator is an IntelliJ IDEA plugin designed to streamline the creation of new modules in [RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) projects. It automates the tedious process of manually creating module structures, updating POM files, and configuring project dependencies.

This plugin helps developers to:
- Create new modules with standardized structure in seconds
- Automatically update all necessary POM files
- Configure dependencies based on predefined templates
- Refresh and import Maven projects seamlessly
- Delete unwanted modules with a single click

## Features

- **One-click Module Generation**: Create complete module structures with a simple dialog
- **Smart Module Detection**: Handles existing modules gracefully without conflicts
- **Flexible Dependency Configuration**: Choose from predefined dependency templates or use defaults
- **Automatic POM Updates**: Updates root POM, modules POM, and admin POM automatically
- **Project Refresh**: Ensures all changes are properly recognized by Maven
- **Package Structure Creation**: Generates standard package structure (controller, service, mapper, etc.)
- **One-click Module Deletion**: Right-click to safely delete generated modules
- **Keyboard Shortcuts**: Provides shortcut (Alt+Q) for quick access to module generation
- **Custom Module Prefix**: Support for configuring custom module name prefixes

## Installation

- **Using IDE built-in plugin system**:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "RuoYi-Vue-Plus Module Fast Generator"</kbd> >
  <kbd>Install</kbd>

## Usage

### Generating a New Module

1. Open your RuoYi-Vue-Plus project in IntelliJ IDEA
2. Choose one of the following to open the module generation dialog:
   - Navigate to <kbd>Tools</kbd> > <kbd>Generate RuoYi Module</kbd>
   - Use shortcut <kbd>Alt+Q</kbd> (Windows/Linux) or <kbd>⌘⌥Q</kbd> (Mac)
   - Right-click in the Project window and select <kbd>Generate Ruoyi-Vue-plus Module</kbd>
3. Enter the module name (prefix will be added automatically if not included)
4. Select dependency configuration template (optional)
5. Click <kbd>OK</kbd>
6. The plugin will create the module and update all necessary files
7. Maven will automatically import the new module

### Deleting a Module

1. In the Project window, right-click on the module you want to delete
2. Select <kbd>Delete Module [module name]</kbd> option
3. Confirm the deletion
4. The plugin will safely delete the module and all its references

### Configuring Dependency Templates

1. Navigate to <kbd>Settings/Preferences</kbd> > <kbd>Tools</kbd> > <kbd>Ruoyi-Vue-plus Module Dependency Configuration</kbd>
2. From this page you can:
   - Add, edit, or delete dependency configuration templates
   - Set the default module prefix
   - Configure default dependencies

## Requirements

- IntelliJ IDEA 2024.1+
- Java 11+
- Maven 3.6+
- RuoYi-Vue-Plus project

## Contributing

Contributions are welcome! If you'd like to contribute, please:
1. Fork the repository
2. Create a feature branch
3. Submit a pull request

## License

This project is licensed under the Apache License 2.0.

---

# RuoYi-Vue-Plus 模块快速生成器

## 简介

RuoYi-Vue-Plus 模块快速生成器是一个 IntelliJ IDEA 插件，专为简化 [RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) 项目中新模块的创建而设计。它自动化了手动创建模块结构、更新 POM 文件和配置项目依赖的繁琐过程。

这个插件可以帮助开发者：
- 几秒钟内创建具有标准结构的新模块
- 自动更新所有必要的 POM 文件
- 基于预定义模板配置依赖
- 无缝刷新和导入 Maven 项目
- 一键删除不需要的模块

## 功能特点

- **一键模块生成**：通过简单对话框创建完整的模块结构
- **智能模块检测**：优雅处理已存在的模块，避免冲突
- **灵活的依赖配置**：可以选择预定义的依赖模板或使用默认值
- **自动 POM 更新**：自动更新根 POM、modules POM 和 admin POM
- **项目刷新**：确保 Maven 正确识别所有更改
- **包结构创建**：生成标准包结构（controller、service、mapper等）
- **一键模块删除**：右键点击即可安全删除已生成的模块
- **快捷键支持**：提供快捷键（Alt+Q）快速访问模块生成功能
- **自定义模块前缀**：支持配置自定义的模块名称前缀

## 安装方法

- **使用 IDE 内置插件系统**：
  
  <kbd>设置/首选项</kbd> > <kbd>插件</kbd> > <kbd>市场</kbd> > <kbd>搜索 "RuoYi-Vue-Plus Module Fast Generator"</kbd> >
  <kbd>安装</kbd>
  
## 使用方法

### 生成新模块

1. 在 IntelliJ IDEA 中打开你的 RuoYi-Vue-Plus 项目
2. 选择以下任一方式打开模块生成对话框：
   - 导航至 <kbd>工具</kbd> > <kbd>生成 RuoYi 模块</kbd>
   - 使用快捷键 <kbd>Alt+Q</kbd>（Windows/Linux）或 <kbd>⌘⌥Q</kbd>（Mac）
   - 在项目窗口中右键点击，选择 <kbd>Generate Ruoyi-Vue-plus Module</kbd>
3. 输入模块名称（如果未包含前缀，将自动添加）
4. 选择依赖配置模板（可选）
5. 点击 <kbd>确定</kbd>
6. 插件将创建模块并更新所有必要文件
7. Maven 将自动导入新模块

### 删除模块

1. 在项目窗口中，右键点击要删除的模块
2. 选择 <kbd>删除模块[模块名]</kbd> 选项
3. 确认删除操作
4. 插件将安全删除模块及其所有相关引用

### 配置依赖模板

1. 导航至 <kbd>设置/首选项</kbd> > <kbd>工具</kbd> > <kbd>Ruoyi-Vue-plus Module Dependency Configuration</kbd>
2. 在此页面可以：
   - 添加、编辑或删除依赖配置模板
   - 设置默认模块前缀
   - 配置默认依赖

## 系统要求

- IntelliJ IDEA 2024.1+
- Java 11+
- Maven 3.6+
- RuoYi-Vue-Plus 项目

<!-- Plugin description end -->

## 参与贡献

欢迎贡献！如果您想贡献，请：
1. Fork 仓库
2. 创建功能分支
3. 提交 Pull Request

## 许可证

本项目采用 Apache License 2.0 许可证。

