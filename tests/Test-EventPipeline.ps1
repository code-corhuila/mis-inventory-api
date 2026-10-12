param([Parameter(Mandatory)][string]$InfraRoot,[Parameter(Mandatory)][string]$InventoryDbRoot,
    [Parameter(Mandatory)][string]$WorkerRoot,[Parameter(Mandatory)][string]$SecurityRoot,
    [switch]$ExpiryAlerts,[string]$GatewayRoot)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -lt 7) { throw 'Se requiere PowerShell 7.' }
$api=Split-Path $PSScriptRoot
$infra=(Resolve-Path $InfraRoot).Path; $db=(Resolve-Path $InventoryDbRoot).Path
$worker=(Resolve-Path $WorkerRoot).Path; $security=(Resolve-Path $SecurityRoot).Path
if($ExpiryAlerts -and -not $GatewayRoot) { throw 'GatewayRoot requerido para aceptacion de alertas.' }
$project='mis-evt-check-'+[guid]::NewGuid().ToString('N').Substring(0,12)
$fixture=Join-Path ([IO.Path]::GetTempPath()) $project
$previous=@{}
function SetVariable($name,$value) {
    $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
    [Environment]::SetEnvironmentVariable($name,$value,'Process')
}
function Compose([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments) {
    docker compose @options @Arguments
    if($LASTEXITCODE -ne 0) { throw 'Fallo comando del proyecto desechable.' }
}
function Sql([string]$query,[switch]$Application) {
    $command=if($Application) { 'export PGPASSWORD="$INVENTORY_APP_PASSWORD"; exec psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -qAt -U inventory_app -d "$POSTGRES_DB"' }
        else { 'exec psql -v ON_ERROR_STOP=1 -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB"' }
    $result=$query | docker compose @options exec -T postgres sh -ec $command
    if($LASTEXITCODE -ne 0) { throw 'Fallo consulta del fixture PostgreSQL.' }
    return $result
}
function WaitSql($query,$expected) {
    for($i=0;$i -lt 60;$i++) { if((Sql $query) -eq $expected) { return }; Start-Sleep -Seconds 2 }
    throw 'No se alcanzo el estado esperado del pipeline.'
}
function NewEvent([switch]$Historical) {
    $lot=[guid]::NewGuid().ToString(); $entry=[guid]::NewGuid().ToString(); $event=[guid]::NewGuid().ToString()
    $actor=[guid]::NewGuid().ToString(); $med=[guid]::NewGuid().ToString(); $supplier=[guid]::NewGuid().ToString()
    $number='EVT-'+$lot.Substring(0,8).ToUpperInvariant()
    $expires=if($ExpiryAlerts) { [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'America/Bogota').AddDays($(if($Historical){-1}else{1})).ToString('yyyy-MM-dd') } else { '2099-01-01' }
    $delivered=if($Historical) { "UPDATE inventory.outbox SET status='DELIVERED',delivered_at=now() WHERE event_id='$event';" } else { '' }
    $query=@"
BEGIN;
INSERT INTO inventory.lot(id,medication_id,supplier_id,lot_number,expires_on,available) VALUES ('$lot','$med','$supplier','$number','$expires',10);
INSERT INTO inventory.entry(id,lot_id,quantity,status,created_by,created_at,changed_by,changed_at) VALUES ('$entry','$lot',10,'ACTIVE','$actor',now(),'$actor',now());
INSERT INTO inventory.movement(id,entry_id,lot_id,quantity,actor,created_at,trace_id) VALUES ('$event','$entry','$lot',10,'$actor',now(),'pipeline-$event');
$delivered
COMMIT;
"@
    if($Historical) { Sql $query | Out-Null } else { Sql $query -Application | Out-Null }
    return @{Lot=$lot;Event=$event}
}
function B64($bytes) { [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') }
$options=@('--env-file',"$infra/env/dev.env",'-p',$project,'-f',"$api/tests/compose.events.yml")
if($ExpiryAlerts) { $options[-1]="$api/tests/compose.expiry.yml" }
$created=$false
try {
    $paths=@("$fixture/mis-security/scripts","$fixture/mis-security/env","$fixture/mis-security/secrets","$fixture/public")
    New-Item -ItemType Directory -Force -Path $paths | Out-Null; $created=$true
    Copy-Item "$security/scripts/Issue-EventServiceToken.ps1" "$fixture/mis-security/scripts/Issue-EventServiceToken.ps1"
    $rsa=[Security.Cryptography.RSA]::Create(2048)
    try {
        [IO.File]::WriteAllText("$fixture/mis-security/secrets/private.pem",$rsa.ExportPkcs8PrivateKeyPem())
        [IO.File]::WriteAllText("$fixture/mis-security/secrets/public.pem",$rsa.ExportSubjectPublicKeyInfoPem())
        $parameters=$rsa.ExportParameters($false)
        $jwk=@{kty='RSA';kid='synthetic-dev';alg='RS256';use='sig';n=(B64 $parameters.Modulus);e=(B64 $parameters.Exponent)}
        [IO.File]::WriteAllText("$fixture/public/jwks.json",(@{keys=@($jwk)} | ConvertTo-Json -Depth 5 -Compress))
    } finally { $rsa.Dispose() }
    [IO.File]::WriteAllText("$fixture/mis-security/env/dev.env","SECURITY_KEY_ID=synthetic-dev`n")
    $relaySubject=[guid]::NewGuid().ToString(); $workerSubject=[guid]::NewGuid().ToString()
    $issuer="$fixture/mis-security/scripts/Issue-EventServiceToken.ps1"
    & $issuer -Service mis-worker -Subject $workerSubject -Register | Out-Null
    & $issuer -Service mis-inventory-relay -Subject $relaySubject -Register | Out-Null
    if($ExpiryAlerts) {
        New-Item -ItemType Directory -Path "$fixture/clients" | Out-Null
        foreach($role in @('ADMIN','INVENTORY','SUPERVISOR','CONSULTATION')) {
            $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
            $header=@{alg='RS256';typ='JWT';kid='synthetic-dev'} | ConvertTo-Json -Compress
            $claims=@{sub=[guid]::NewGuid().ToString();iss='mis-security';aud='mis-api';roles=@($role);iat=$now;exp=$now+3600;jti=[guid]::NewGuid().ToString()} | ConvertTo-Json -Compress
            $inputValue=(B64 ([Text.Encoding]::UTF8.GetBytes($header)))+'.'+(B64 ([Text.Encoding]::UTF8.GetBytes($claims)))
            $signature=[MediStockSecurityEventSigner]::Sign("$fixture/mis-security/secrets/private.pem","$fixture/mis-security/secrets/public.pem",[Text.Encoding]::UTF8.GetBytes($inputValue))
            [IO.File]::WriteAllText("$fixture/clients/$role.jwt",$inputValue+'.'+(B64 $signature),[Text.Encoding]::ASCII)
        }
        Copy-Item "$fixture/mis-worker/secrets/dev/mis-worker/service.jwt" "$fixture/clients/WORKER.jwt"
        foreach($pair in @(@('MIS_GATEWAY_PATH',(Resolve-Path $GatewayRoot).Path),@('EVT_CLIENT_PATH',"$fixture/clients"),
            @('GATEWAY_HOST_PORT','0'),@('GATEWAY_ALLOWED_ORIGINS','http://localhost:4200'),
            @('WORKER_EXPIRY_ENABLED','true'),@('WORKER_EXPIRY_SCAN_ENABLED','true'),
            @('WORKER_EXPIRY_WINDOW_DAYS','30'),@('WORKER_EXPIRY_INTERVAL_SECONDS','60'),@('WORKER_EXPIRY_MAX_PAGES','100'))) { SetVariable $pair[0] $pair[1] }
    }
    $password=[guid]::NewGuid().ToString('N'); $passwordFile="$fixture/relay-password"
    [IO.File]::WriteAllText($passwordFile,$password)
    foreach($pair in @(@('PG_HOST_PORT','0'),@('MIS_INFRA_POSTGRES_PATH',$infra),@('MIS_INVENTORY_DB_PATH',$db),@('MIS_WORKER_PATH',$worker),
        @('INVENTORY_IMAGE',"${project}-inventory"),@('WORKER_IMAGE',"${project}-worker"),@('EVT_PUBLIC_PATH',"$fixture/public"),
        @('WORKER_KEY_ID','synthetic-dev'),@('WORKER_ENVIRONMENT','dev'),@('WORKER_SERVICE_SUBJECT',$workerSubject),
        @('RELAY_KEY_ID','synthetic-dev'),@('RELAY_ENVIRONMENT','dev'),@('RELAY_SERVICE_SUBJECT',$relaySubject),
        @('RELAY_PASSWORD_PATH',$passwordFile),@('RELAY_WORKER_ORIGIN','http://ack-proxy:8080'),
        @('WORKER_CREDENTIALS_PATH',"$fixture/mis-worker/secrets/dev/mis-worker"),
        @('RELAY_CREDENTIALS_PATH',"$fixture/mis-inventory-api/secrets/dev/mis-inventory-relay"))) { SetVariable $pair[0] $pair[1] }
    # The model contains fixture passwords: keep it in memory, never print it.
    $json=Compose @('config','--format','json'); $model=($json -join "`n") | ConvertFrom-Json
    if($model.name -ne $project -or $model.volumes.'postgres-data'.name -ne "${project}_postgres-data") { throw 'Proyecto/volumen de prueba ajeno.' }
    foreach($service in @('inventory','worker','inventory-relay')) { if($model.services.$service.ports) { throw 'Puerto publico inesperado.' } }
    Compose @('build','inventory','worker')
    if($ExpiryAlerts) { Compose @('build','gateway','expiry-check') }
    Compose @('up','-d','--no-build','--pull','never','--wait','--wait-timeout','120','postgres')
    Compose @('run','--rm','inventory-db-migrate')
    Sql "CREATE ROLE inventory_relay_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$password'; GRANT inventory_relay TO inventory_relay_app;" | Out-Null
    Compose @('up','-d','--no-build','--pull','never','--wait','--wait-timeout','180','inventory','worker','ack-proxy','inventory-relay')
    if($ExpiryAlerts) { Compose @('up','-d','--no-build','--wait','gateway') }
    $first=NewEvent
    WaitSql "SELECT status FROM inventory.outbox WHERE event_id='$($first.Event)';" 'DELIVERED'
    $state=Sql "SELECT (SELECT count(*) FROM inventory.stock_event_inbox WHERE event_id='$($first.Event)' AND processed_by='$workerSubject') || ',' || available || ',' || (SELECT (attempts>=2)::int FROM inventory.outbox WHERE event_id='$($first.Event)') || ',' || (SELECT (c.last_sequence=o.sequence)::int FROM inventory.stock_event_checkpoint c JOIN inventory.outbox o ON o.lot_id=c.lot_id WHERE o.event_id='$($first.Event)') || ',' || (SELECT count(*) FROM inventory.movement WHERE lot_id='$($first.Lot)') FROM inventory.lot WHERE id='$($first.Lot)';"
    if($state -ne '1,10,1,1,1') { throw 'Confirmacion perdida duplico efecto o no produjo retry/checkpoint.' }
    if($ExpiryAlerts) {
        WaitSql "SELECT state||','||severity FROM inventory.expiry_alert WHERE lot_id='$($first.Lot)';" 'OPEN,EXPIRING'
        $alertId=Sql "SELECT id FROM inventory.expiry_alert WHERE lot_id='$($first.Lot)';"
    }
    $drops=Compose @('exec','-T','ack-proxy','python','-c',"import json,urllib.request; print(json.load(urllib.request.urlopen('http://localhost:8080/observations'))['droppedAcknowledgements'])")
    if($drops -ne '1') { throw 'No se ejercito la perdida de confirmacion.' }
    Compose @('stop','worker')
    $second=NewEvent
    WaitSql "SELECT (status='PENDING' AND attempts>=1)::int FROM inventory.outbox WHERE event_id='$($second.Event)';" '1'
    Compose @('stop','-t','25','inventory-relay')
    if($ExpiryAlerts) { $historical=NewEvent -Historical }
    Compose @('start','worker','inventory-relay')
    WaitSql "SELECT status FROM inventory.outbox WHERE event_id='$($second.Event)';" 'DELIVERED'
    $state=Sql "SELECT (SELECT count(*) FROM inventory.stock_event_inbox WHERE event_id='$($second.Event)') || ',' || available FROM inventory.lot WHERE id='$($second.Lot)';"
    if($state -ne '1,10') { throw 'Reinicio no preservo efecto/saldo.' }
    if($ExpiryAlerts) {
        WaitSql "SELECT state||','||severity FROM inventory.expiry_alert WHERE lot_id='$($historical.Lot)';" 'OPEN,EXPIRED'
        $timed=NewEvent -Historical
        WaitSql "SELECT state||','||severity FROM inventory.expiry_alert WHERE lot_id='$($timed.Lot)';" 'OPEN,EXPIRED'
        foreach($lot in @($historical,$timed)) {
            if((Sql "SELECT (SELECT count(*) FROM inventory.stock_event_inbox WHERE event_id='$($lot.Event)')||','||available||','||(SELECT count(*) FROM inventory.movement WHERE lot_id='$($lot.Lot)') FROM inventory.lot WHERE id='$($lot.Lot)';") -ne '0,10,1') { throw 'Barrido dependio de evento o altero stock/historial.' }
        }
        Compose @('run','--rm','--no-deps','expiry-check','exit',$first.Lot)
        WaitSql "SELECT status FROM inventory.outbox WHERE lot_id='$($first.Lot)' AND payload->>'movementType'='EXIT';" 'DELIVERED'
        WaitSql "SELECT state||','||on_hand_quantity FROM inventory.expiry_alert WHERE lot_id='$($first.Lot)';" 'RESOLVED,0'
        if((Sql "SELECT (SELECT count(*) FROM inventory.expiry_alert WHERE lot_id='$($first.Lot)' AND id='$alertId')||','||available||','||(SELECT count(*) FROM inventory.movement WHERE lot_id='$($first.Lot)')||','||(SELECT count(*) FROM inventory.stock_event_inbox WHERE lot_id='$($first.Lot)') FROM inventory.lot WHERE id='$($first.Lot)';") -ne '1,0,2,2') { throw 'Resolucion/replay duplico evidencia o descuento.' }
        Compose @('run','--rm','--no-deps','expiry-check','read',$first.Lot,$alertId)
        Write-Output 'PASS: alertas reales PostgreSQL/Inventory/Worker/relay/Gateway: retry, restart, barrido sin evento, salida/resolucion, roles y contratos. Login/JWKS/referencias son fixtures.'
    }
    $id=Compose @('ps','-q','inventory-relay'); $details=(docker inspect $id | ConvertFrom-Json)[0]
    $bindings=@($details.HostConfig.PortBindings.PSObject.Properties).Count
    if($details.Config.User -ne '10001:10001' -or -not $details.HostConfig.ReadonlyRootfs -or $bindings -ne 0) { throw 'Restricciones relay incorrectas.' }
    if($details.HostConfig.CapDrop -notcontains 'ALL' -or $details.HostConfig.SecurityOpt -notcontains 'no-new-privileges:true') { throw 'Capacidades relay inseguras.' }
    foreach($mount in $details.Mounts | Where-Object Type -eq 'bind') { if($mount.RW) { throw 'Montaje privado de escritura.' } }
    Write-Output 'PASS: pipeline real Inventory/Worker/relay; firmas del emisor Security con claves sinteticas, ACK perdido y recuperacion tras reinicio. Dependencias de referencias/JWKS simuladas.'
} finally {
    if($created) {
        docker compose @options down -v --remove-orphans | Out-Null
        $resolved=[IO.Path]::GetFullPath($fixture); $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if(-not $resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notlike 'mis-evt-check-*') { throw 'Limpieza fuera del fixture.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    foreach($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
