# 设置 JAVA_HOME
[System.Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot', 'User')

# 获取当前用户 PATH
$currentPath = [System.Environment]::GetEnvironmentVariable('Path', 'User')

# Java bin 路径
$javaPath = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin'

# 检查是否已经在 PATH 中
if ($currentPath -notlike "*$javaPath*") {
    # 添加到 PATH 开头
    $newPath = "$javaPath;$currentPath"
    [System.Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
    Write-Host "Java bin 已添加到 PATH"
} else {
    Write-Host "Java bin 已经在 PATH 中"
}

Write-Host "`n环境变量配置完成！"
Write-Host "JAVA_HOME = $env:JAVA_HOME"
Write-Host "`n请重新打开终端使环境变量生效。"
