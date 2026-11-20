# 本地编译 notes-app 并上传 jar（服务器只负责换包，不再编译）
#   .\build.ps1                 编译 -> 上传到 /root/notes-app.jar -> 删掉本地 target
#   .\build.ps1 -KeepTarget     保留 target（排查用）
#   .\build.ps1 -SkipUpload     只编译，不上传
# 部署： ssh root@47.104.210.93 "bash /opt/deploy/app.sh dev --jar /root/notes-app.jar"
param(
  [switch]$KeepTarget,
  [switch]$SkipUpload
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = 'D:\User\study\Java\jdk\jdk-21'
$sshKey = 'C:\Users\J\.ssh\bytelove_ed25519'
$remote = 'root@47.104.210.93'
$jar = Join-Path $root 'target\notes-app.jar'

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

Push-Location $root
try {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  Write-Host '[1/3] mvn package'
  mvn -B -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw "mvn 退出码 $LASTEXITCODE" }
  if (-not (Test-Path $jar)) { throw "没生成 $jar" }
  $size = (Get-Item $jar).Length / 1MB
  Write-Host ("      完成 {0:N0}s，jar {1:N1}MB" -f $sw.Elapsed.TotalSeconds, $size)

  if (-not $SkipUpload) {
    Write-Host '[2/3] 上传 -> /root/notes-app.jar'
    scp -i $sshKey -o StrictHostKeyChecking=no -q $jar "${remote}:/root/notes-app.jar"
    if ($LASTEXITCODE -ne 0) { throw "scp 退出码 $LASTEXITCODE" }
    Write-Host ("      上传 {0:N0}s" -f $sw.Elapsed.TotalSeconds)
  }

  if (-not $KeepTarget) {
    Write-Host '[3/3] 删除 target'
    Remove-Item -Recurse -Force (Join-Path $root 'target')
  }
  else {
    Write-Host '[3/3] 保留 target'
  }
  Write-Host ("DONE {0:N0}s" -f $sw.Elapsed.TotalSeconds)
}
finally {
  Pop-Location
}
