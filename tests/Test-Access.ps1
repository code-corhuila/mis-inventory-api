param([Parameter(Mandatory)][string]$BaseUrl,[Parameter(Mandatory)][string]$SecurityUrl,[Parameter(Mandatory)][string]$SecurityEnv)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -lt 7) { throw 'Se requiere PowerShell 7.' }
$settings=@{}
Get-Content -LiteralPath $SecurityEnv | ForEach-Object { if($_ -match '^([A-Z_]+)=(.*)$') { $settings[$matches[1]]=$matches[2] } }
function Send($method,$path,$token,$expected) {
    $trace=[guid]::NewGuid().ToString(); $headers=@{'X-Correlation-Id'=$trace;'X-User-Role'='ADMIN'}
    if($token) { $headers.Authorization='Bearer '+$token }
    $args=@{Uri=$BaseUrl.TrimEnd('/')+$path;Method=$method;Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=20}
    if($method -eq 'POST') { $args.Body='{}'; $args.ContentType='application/json' }
    $response=Invoke-WebRequest @args; $data=$response.Content | ConvertFrom-Json
    if($response.StatusCode -ne $expected) { throw "HTTP $method ${path}: $($response.StatusCode), esperado $expected" }
    if($response.Headers['X-Correlation-Id'][0] -ne $trace) { throw 'Correlacion perdida.' }
    if($expected -ge 400 -and ($data.traceId -ne $trace -or -not $data.error)) { throw 'Error fuera de contrato.' }
}
Send GET '/health' $null 200
Send GET '/api/v1/stock' $null 401
Send GET '/api/v1/stock' 'invalid' 401
foreach($entry in @(@('admin','ADMIN'),@('inventario','INVENTORY'),@('supervisor','SUPERVISOR'),@('consulta','CONSULTATION'))) {
    $password=$settings['BOOTSTRAP_'+$entry[1]+'_PASSWORD']; if(-not $password) { throw 'Falta configuracion bootstrap.' }
    $login=Invoke-RestMethod -Uri ($SecurityUrl.TrimEnd('/')+'/api/v1/auth/login') -Method Post -ContentType 'application/json' -Body (@{username=$entry[0];password=$password} | ConvertTo-Json -Compress)
    Send GET '/api/v1/stock?page=1&limit=1' $login.accessToken 200
    $history=if($entry[1] -eq 'CONSULTATION') {403} else {200}
    Send GET '/api/v1/movements?page=1&limit=1' $login.accessToken $history
    if($entry[1] -in @('SUPERVISOR','CONSULTATION')) { Send POST '/api/v1/entries' $login.accessToken 403; Send POST '/api/v1/exits' $login.accessToken 403 }
}
Write-Output 'PASS: salud, JWT real, correlacion y permisos de lectura/escritura rechazada. Sin crear fixtures.'
