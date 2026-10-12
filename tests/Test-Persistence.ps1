param([Parameter(Mandatory)][string]$InfraRoot,[Parameter(Mandatory)][string]$InventoryDbRoot)
$ErrorActionPreference='Stop'
$infra=(Resolve-Path -LiteralPath $InfraRoot).Path
$db=(Resolve-Path -LiteralPath $InventoryDbRoot).Path
$api=Split-Path $PSScriptRoot
$previous=@{}
foreach($name in @('PG_HOST_PORT','MIS_INFRA_POSTGRES_PATH','MIS_INVENTORY_DB_PATH','INV_RELAY_DB_PASSWORD')) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
$env:PG_HOST_PORT='0'
$env:MIS_INFRA_POSTGRES_PATH=$infra.Replace('\','/')
$env:MIS_INVENTORY_DB_PATH=$db.Replace('\','/')
$env:INV_RELAY_DB_PASSWORD=[guid]::NewGuid().ToString('N')
$project='mis-inv-api-check-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$options=@('--env-file',(Join-Path $infra 'env/dev.env'),'-p',$project,'-f',(Join-Path $api 'tests/compose.persistence.yml'))
try {
    docker volume create mis-publication-maven-cache | Out-Null
    if($LASTEXITCODE -ne 0) { throw 'No pudo preparar cache Maven.' }
    docker compose @options up -d --wait --wait-timeout 120 postgres
    if($LASTEXITCODE -ne 0) { throw 'No inicio PostgreSQL de prueba.' }
    docker compose @options run --rm inventory-db-migrate
    if($LASTEXITCODE -ne 0) { throw 'Fallo migracion de prueba.' }
    # El login solo existe en la base desechable; su password no se imprime ni versiona.
    $sql="CREATE ROLE inventory_relay_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$env:INV_RELAY_DB_PASSWORD'; GRANT inventory_relay TO inventory_relay_app;"
    $sql | docker compose @options exec -T postgres sh -ec 'exec psql -v ON_ERROR_STOP=1 -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
    if($LASTEXITCODE -ne 0) { throw 'No pudo preparar login relay sintetico.' }
    docker compose @options run --rm --no-deps inventory-tests
    if($LASTEXITCODE -ne 0) { throw 'Fallo verificacion JDBC.' }
    Write-Output 'PASS: PostgreSQL desechable; pruebas con inventory_app e inventory_relay_app.'
} finally {
    # Solo se retira este proyecto aleatorio; la cache Maven externa permanece.
    docker compose @options down -v
    foreach($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
