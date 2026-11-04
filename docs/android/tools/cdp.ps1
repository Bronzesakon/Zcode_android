# CDP 真机求值助手 —— 通过 adb forward 到 WebView devtools socket，在页面里执行 JS 并回传结果
#
# 前置（只需一次）：
#   adb forward tcp:39385 localabstract:webview_devtools_remote_<pid>
#   （找 pid：adb shell "cat /proc/net/unix | grep webview_devtools"）
#
# 用法：
#   pwsh -File cdp.ps1 -Expr "JSON.stringify(history.state)"
#   pwsh -File cdp.ps1 -Expr "1+1" -Raw
param(
    [Parameter(Mandatory = $true)][string]$Expr,
    [int]$Port = 39385,
    [string]$TargetWs = '',
    [switch]$Raw
)

$ErrorActionPreference = 'Stop'

if (-not $TargetWs) {
    $list = (Invoke-WebRequest -Uri "http://127.0.0.1:$Port/json" -TimeoutSec 8 -UseBasicParsing).Content | ConvertFrom-Json
    $page = $list | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
    if (-not $page) { throw 'no page target (WebView 是否还活着？pid 变了要重新 forward)' }
    $TargetWs = $page.webSocketDebuggerUrl
}

$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$ct = [System.Threading.CancellationToken]::None
$ws.ConnectAsync([Uri]$TargetWs, $ct).Wait()

$payload = @{
    id     = 1
    method = 'Runtime.evaluate'
    params = @{ expression = $Expr; returnByValue = $true; awaitPromise = $true }
} | ConvertTo-Json -Compress -Depth 8

$bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
$ws.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait()

# 收帧：拿满一整条消息（大响应会分片）
$sb = New-Object System.Text.StringBuilder
$buf = New-Object byte[] 65536
do {
    $seg = [ArraySegment[byte]]::new($buf)
    $res = $ws.ReceiveAsync($seg, $ct).Result
    [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count))
} while (-not $res.EndOfMessage)
$ws.Dispose()

$text = $sb.ToString()
if ($Raw) { $text; exit 0 }

$obj = $text | ConvertFrom-Json
if ($obj.result -and $obj.result.exceptionDetails) {
    'EXCEPTION: ' + ($obj.result.exceptionDetails | ConvertTo-Json -Compress -Depth 6)
    exit 2
}
if ($obj.result -and $obj.result.result) {
    $v = $obj.result.result
    if ($v.type -eq 'object' -and $null -ne $v.preview) { $v.preview | ConvertTo-Json -Compress -Depth 6 }
    elseif ($null -ne $v.value) { [string]$v.value }
    else { $v | ConvertTo-Json -Compress -Depth 6 }
} else {
    $text
}
