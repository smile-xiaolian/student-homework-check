param(
    [string]$ApiBase = 'http://localhost:8080/api/v1',
    [string]$LoginName = 'teacher01',
    [string]$Password = 'Teacher@123'
)

$ErrorActionPreference = 'Stop'

$health = Invoke-RestMethod -Uri "$ApiBase/system/health" -TimeoutSec 5
if ($health.status -ne 'UP') { throw "系统健康状态异常：$($health.status)" }

$body = @{ loginName = $LoginName; password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$ApiBase/auth/login" -ContentType 'application/json' -Body $body
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$courses = Invoke-RestMethod -Uri "$ApiBase/courses?page=1&size=20" -Headers $headers
$me = Invoke-RestMethod -Uri "$ApiBase/me" -Headers $headers

Write-Host "冒烟检查通过：$($me.displayName) 登录成功，可见课程 $($courses.items.Count) 门。"
