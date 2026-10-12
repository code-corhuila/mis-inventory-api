param([Parameter(Mandatory)][string]$BaseUrl,[Parameter(Mandatory)][string]$GatewayUrl,[Parameter(Mandatory)][string]$SecurityEnv,[string]$ContractsImage='mis-inventory-contracts')
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -lt 7) { throw 'Se requiere PowerShell 7.' }
$settings=@{}; $samples=[Collections.Generic.List[object]]::new()
Get-Content -LiteralPath $SecurityEnv | ForEach-Object { if($_ -match '^([A-Z_]+)=(.*)$') { $settings[$matches[1]]=$matches[2] } }
function Check($value,$message) { if(-not $value) { throw $message } }
function Send($method,$path,$body,$token,$key,$expected) {
    $trace=[guid]::NewGuid().ToString(); $headers=@{'X-Correlation-Id'=$trace;'X-User-Role'='ADMIN'}
    if($token) { $headers.Authorization='Bearer '+$token }
    if($key) { $headers['Idempotency-Key']=$key }
    $options=@{Uri=$BaseUrl.TrimEnd('/')+$path;Method=$method;Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=20}
    if($null -ne $body) { $options.Body=($body | ConvertTo-Json -Compress); $options.ContentType='application/json' }
    $response=Invoke-WebRequest @options; $data=$response.Content | ConvertFrom-Json
    Check ($response.StatusCode -eq $expected) "HTTP $method ${path}: $($response.StatusCode), esperado $expected"
    Check ($response.Headers['X-Correlation-Id'][0] -eq $trace) 'Correlacion perdida.'
    if($expected -ge 400) { Check ($data.traceId -eq $trace -and $data.error) 'Error fuera de contrato.'; $schema='Error' }
    elseif($path.StartsWith('/api/v1/stock')) { $schema='StockPage' }
    elseif($path.StartsWith('/api/v1/movements')) { $schema='MovementPage' }
    else { $schema=if($path.StartsWith('/api/v1/exits')) {'Exit'} else {'Entry'} }
    $samples.Add(@{schema=$schema;body=$data}); return @{Data=$data;Headers=$response.Headers}
}
$tokens=@{}
foreach($entry in @(@('admin','ADMIN'),@('inventario','INVENTORY'),@('supervisor','SUPERVISOR'),@('consulta','CONSULTATION'))) {
    $password=$settings['BOOTSTRAP_'+$entry[1]+'_PASSWORD']; Check ($password) 'Falta password bootstrap.'
    $login=Invoke-RestMethod -Uri ($GatewayUrl.TrimEnd('/')+'/api/v1/auth/login') -Method Post -ContentType 'application/json' -Body (@{username=$entry[0];password=$password} | ConvertTo-Json -Compress)
    $tokens[$entry[1]]=$login.accessToken
}
$suffix=[guid]::NewGuid().ToString('N').ToUpperInvariant()
$actor=(Invoke-RestMethod -Uri ($GatewayUrl.TrimEnd('/')+'/api/v1/auth/me') -Headers @{Authorization='Bearer '+$tokens.INVENTORY}).id
$upstreamHeaders=@{Authorization='Bearer '+$tokens.ADMIN;'Idempotency-Key'="med-$suffix"}
$med=@{code="INV-$suffix";name='Medicamento sintetico oficial';activeIngredient='Sintetico';pharmaceuticalForm='Tableta';concentration='10 mg';unitOfMeasure='Unidad'}
$medication=Invoke-RestMethod -Uri ($GatewayUrl.TrimEnd('/')+'/api/v1/medications') -Method Post -Headers $upstreamHeaders -ContentType 'application/json' -Body ($med | ConvertTo-Json -Compress)
$upstreamHeaders['Idempotency-Key']="supplier-$suffix"
$supplier=Invoke-RestMethod -Uri ($GatewayUrl.TrimEnd('/')+'/api/v1/suppliers') -Method Post -Headers $upstreamHeaders -ContentType 'application/json' -Body (@{documentNumber="INV-$suffix";name='Proveedor sintetico oficial'} | ConvertTo-Json -Compress)
$body=@{medicationId=$medication.id;supplierId=$supplier.id;lotNumber="LOT-$suffix";expiresOn='2099-01-01';quantity=10}
foreach($role in @('SUPERVISOR','CONSULTATION')) { [void](Send POST '/api/v1/entries' $body $tokens[$role] "entry-$suffix" 403) }
[void](Send POST '/api/v1/entries' $body $null "entry-$suffix" 401)
[void](Send POST '/api/v1/entries' $body 'invalid' "entry-$suffix" 401)
[void](Send POST '/api/v1/entries' $body $tokens.INVENTORY $null 400)
foreach($invalid in @(@{quantity='10'},@{quantity=1.5},@{unknown=$true},@{medicationId=[guid]::NewGuid().ToString()},@{supplierId=[guid]::NewGuid().ToString()})) {
    $copy=$body.Clone(); foreach($field in $invalid.Keys) { $copy[$field]=$invalid[$field] }
    $expected=if($invalid.ContainsKey('medicationId') -or $invalid.ContainsKey('supplierId')) {422} else {400}
    [void](Send POST '/api/v1/entries' $copy $tokens.INVENTORY "invalid-$suffix" $expected)
}
$created=Send POST '/api/v1/entries' $body $tokens.INVENTORY "entry-$suffix" 201
$id=$created.Data.id; $lot=$created.Data.lotId; $path="/api/v1/entries/$id"
Check ($created.Data.status -eq 'PENDING' -and $created.Headers.Location[0] -eq $path) 'Preparacion incorrecta.'
Check ($created.Data.createdBy -eq $actor) 'Actor de entrada incorrecto.'
$stockPath="/api/v1/stock?lotId=$lot"
$stock=(Send GET $stockPath $null $tokens.INVENTORY $null 200).Data.data[0]
Check ($stock.onHandQuantity -eq 0 -and $stock.pendingQuantity -eq 10) 'Preparar altero stock.'
foreach($role in $tokens.Keys) {
    [void](Send GET $stockPath $null $tokens[$role] $null 200)
    $expected=if($role -eq 'CONSULTATION') {403} else {200}
    [void](Send GET $path $null $tokens[$role] $null $expected)
    [void](Send GET "/api/v1/movements?lotId=$lot" $null $tokens[$role] $null $expected)
    if($role -in @('SUPERVISOR','CONSULTATION')) { [void](Send POST "$path/activate" @{} $tokens[$role] "activate-$suffix" 403) }
}
$different=$body.Clone(); $different.quantity=11
[void](Send POST '/api/v1/entries' $different $tokens.INVENTORY "entry-$suffix" 409)
[void](Send POST "$path/activate" @{quantity=20} $tokens.INVENTORY "activate-$suffix" 400)
Check ((Send POST "$path/activate" @{} $tokens.INVENTORY "activate-$suffix" 200).Data.status -eq 'ACTIVE') 'No activo.'
[void](Send POST "$path/activate" @{} $tokens.INVENTORY "activate-$suffix" 200)
[void](Send POST "$path/activate" @{} $tokens.INVENTORY "activate-again-$suffix" 200)
[void](Send POST "$path/cancel" @{} $tokens.INVENTORY "cancel-active-$suffix" 422)
Check ((Send POST '/api/v1/entries' $body $tokens.INVENTORY "entry-$suffix" 200).Data.status -eq 'PENDING') 'Replay no preservo snapshot.'
$exitBody=@{lotId=$lot;quantity=2;reason='Entrega sintetica'}
foreach($role in @('SUPERVISOR','CONSULTATION')) { [void](Send POST '/api/v1/exits' $exitBody $tokens[$role] "exit-$suffix" 403) }
$exit=Send POST '/api/v1/exits' $exitBody $tokens.INVENTORY "exit-$suffix" 201
Check ($exit.Data.remainingQuantity -eq 8 -and $exit.Headers.Location[0] -eq '/api/v1/exits/'+$exit.Data.id) 'Salida incorrecta.'
Check ($exit.Data.createdBy -eq $actor) 'Actor de salida incorrecto.'
Check ((Send POST '/api/v1/exits' $exitBody $tokens.INVENTORY "exit-$suffix" 200).Data.id -eq $exit.Data.id) 'Salida duplicada.'
$different=$exitBody.Clone(); $different.quantity=3
[void](Send POST '/api/v1/exits' $different $tokens.INVENTORY "exit-$suffix" 409)
$different.quantity=99; [void](Send POST '/api/v1/exits' $different $tokens.INVENTORY "large-$suffix" 422)
$pending=Send POST '/api/v1/entries' $body $tokens.INVENTORY "pending-$suffix" 201
$cancelPath='/api/v1/entries/'+$pending.Data.id
Check ((Send POST "$cancelPath/cancel" @{} $tokens.INVENTORY "cancel-$suffix" 200).Data.status -eq 'CANCELLED') 'Cancelacion incorrecta.'
[void](Send POST "$cancelPath/cancel" @{} $tokens.INVENTORY "cancel-$suffix" 200)
[void](Send POST "$cancelPath/activate" @{} $tokens.INVENTORY "reactivate-$suffix" 422)
$stock=(Send GET $stockPath $null $tokens.INVENTORY $null 200).Data.data[0]
Check ($stock.onHandQuantity -eq 8 -and $stock.availableQuantity -eq 8 -and $stock.pendingQuantity -eq 0) 'Saldo final incorrecto.'
$history=(Send GET "/api/v1/movements?lotId=$lot" $null $tokens.SUPERVISOR $null 200).Data
Check ($history.meta.total -eq 2 -and @($history.data | Where-Object type -eq 'ENTRY').Count -eq 1 -and @($history.data | Where-Object type -eq 'EXIT').Count -eq 1) 'Movimientos duplicados.'
[void](Send GET '/api/v1/stock?page=1&page=2' $null $tokens.INVENTORY $null 400)
[void](Send GET '/api/v1/movements?unknown=true' $null $tokens.INVENTORY $null 400)
$temp=[IO.Path]::GetTempFileName(); $repo=Split-Path $PSScriptRoot
try {
    [IO.File]::WriteAllText($temp,($samples | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    docker run --rm --mount "type=bind,source=$repo,target=/workspace,readonly" --mount "type=bind,source=$temp,target=/samples.json,readonly" $ContractsImage /samples.json
    if($LASTEXITCODE -ne 0) { throw 'Contrato real invalido.' }
} finally { Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue }
Write-Output "PASS: JWT/roles, referencias reales, entradas/salidas, replays, saldo 8, dos movimientos y contratos. Fixtures: medicamento $($medication.id), proveedor $($supplier.id), lote $lot, entradas $id/$($pending.Data.id), salida $($exit.Data.id)."
