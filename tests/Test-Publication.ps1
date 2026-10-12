param([Parameter(Mandatory)][string]$InfraEnv,[Parameter(Mandatory)][string]$SecurityEnv,[Parameter(Mandatory)][string]$GatewayUrl,[Parameter(Mandatory)][string]$DockerNetwork)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -lt 7) { throw 'Se requiere PowerShell 7.' }
$api=Split-Path $PSScriptRoot; $settings=@{}
Get-Content -LiteralPath $InfraEnv | ForEach-Object { if($_ -match '^([A-Z_]+)=(.*)$') { $settings[$matches[1]]=$matches[2] } }
if(-not $settings.INVENTORY_APP_PASSWORD -or -not $settings.PG_DATABASE) { throw 'Falta configuracion de infraestructura.' }
$name='mis-inventory-publication-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$previous=$env:INVENTORY_DB_PASSWORD
try {
    docker build -t mis-inventory-official-check -f (Join-Path $api 'deploy/Dockerfile') $api
    if($LASTEXITCODE -ne 0) { throw 'Fallo build oficial.' }
    docker build -t mis-inventory-contracts -f (Join-Path $api 'tests/Dockerfile.contracts') $api
    if($LASTEXITCODE -ne 0) { throw 'Fallo build de contratos.' }
    $env:INVENTORY_DB_PASSWORD=$settings.INVENTORY_APP_PASSWORD
    $args=@('run','-d','--name',$name,'--network',$DockerNetwork,'-p','127.0.0.1:0:8080','--env','INVENTORY_DB_PASSWORD',
        '--env',('INVENTORY_DB_URL=jdbc:postgresql://postgres:5432/'+$settings.PG_DATABASE),
        '--env','INVENTORY_JWKS_URL=http://security:8080/api/v1/auth/jwks',
        '--env','INVENTORY_CATALOG_URL=http://catalog:8080','--env','INVENTORY_SUPPLIERS_URL=http://suppliers:8080','mis-inventory-official-check')
    docker @args | Out-Null
    if($LASTEXITCODE -ne 0) { throw 'Fallo inicio temporal.' }
    $address=docker port $name 8080/tcp
    if($LASTEXITCODE -ne 0) { throw 'Sin puerto temporal.' }
    $base='http://'+$address.Trim(); $ready=$false
    for($i=0;$i -lt 60;$i++) {
        try { $response=Invoke-WebRequest ($base+'/health') -SkipHttpErrorCheck -TimeoutSec 2; if($response.StatusCode -eq 200) { $ready=$true; break } } catch { }
        Start-Sleep -Seconds 2
    }
    if(-not $ready) { throw 'No recupero salud temporal.' }
    & (Join-Path $PSScriptRoot 'Test-Access.ps1') -BaseUrl $base -SecurityUrl $GatewayUrl -SecurityEnv $SecurityEnv
    & (Join-Path $PSScriptRoot 'Test-Integration.ps1') -BaseUrl $base -GatewayUrl $GatewayUrl -SecurityEnv $SecurityEnv
    $uid=docker exec $name id -u
    if($LASTEXITCODE -ne 0 -or $uid.Trim() -ne '10001') { throw 'Usuario runtime incorrecto.' }
    Write-Output 'PASS: imagen oficial, acceso/aceptacion reales y UID 10001; fixtures nuevos conservados.'
} finally {
    # Solo este contenedor temporal; nunca los servicios o volumenes del sistema.
    docker rm -f $name 2>$null | Out-Null
    [Environment]::SetEnvironmentVariable('INVENTORY_DB_PASSWORD',$previous,'Process')
}
