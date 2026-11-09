param([switch]$NoBuild)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw '未找到 Docker。请先安装并启动 Docker Desktop。'
}

Push-Location $root
try {
    if (-not (Test-Path '.env')) {
        Copy-Item '.env.example' '.env'
        Write-Host '已从 .env.example 创建 .env，请在生产环境修改默认密码。'
    }

    $composeArgs = @('compose', 'up', '-d')
    if (-not $NoBuild) { $composeArgs += '--build' }
    & docker @composeArgs
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 启动失败。' }

    $deadline = (Get-Date).AddMinutes(5)
    do {
        try {
            $health = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 3
            if ($health.status -eq 'UP') { break }
            Start-Sleep -Seconds 3
        } catch {
            Start-Sleep -Seconds 3
        }
    } while ((Get-Date) -lt $deadline)

    if ((Get-Date) -ge $deadline) {
        throw '服务在 5 分钟内未就绪，请运行 docker compose logs 检查日志。'
    }

    Write-Host '系统已就绪：http://localhost:8088'
    Write-Host '接口文档：http://localhost:8080/swagger-ui.html'
} finally {
    Pop-Location
}
