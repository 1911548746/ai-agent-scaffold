<#
.SYNOPSIS
构建并启动本地 ADK Web 调试模块。

.DESCRIPTION
脚本激活 adk-web Maven Profile，只构建开发模块及其依赖，然后运行独立的
ai-agent-scaffold-adk-web.jar。实际发送对话仍可能调用模型或工具。

.EXAMPLE
./scripts/start-adk-web.ps1

.EXAMPLE
./scripts/start-adk-web.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
    [Parameter()]
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($env:MODEL_API_KEY)) {
    throw "启动 ADK Web 前必须设置 MODEL_API_KEY 环境变量"
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $repositoryRoot `
    "ai-agent-scaffold-adk-web\target\ai-agent-scaffold-adk-web.jar"
$port = if ([string]::IsNullOrWhiteSpace($env:ADK_WEB_PORT)) {
    8000
}
else {
    $env:ADK_WEB_PORT
}

Push-Location $repositoryRoot
try {
    if (-not $SkipBuild) {
        Write-Host "正在构建 ADK Web 开发模块..." -ForegroundColor Cyan
        & mvn -Padk-web -pl ai-agent-scaffold-adk-web -am package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            throw "ADK Web 开发模块构建失败，Maven 退出码：$LASTEXITCODE"
        }
    }

    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw "未找到 ADK Web 可执行包，请先移除 -SkipBuild 完成构建"
    }

    Write-Host "ADK Web 启动后访问：http://127.0.0.1:$port/dev-ui" -ForegroundColor Green
    & java -jar $jarPath
    if ($LASTEXITCODE -ne 0) {
        throw "ADK Web 进程异常退出，退出码：$LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
