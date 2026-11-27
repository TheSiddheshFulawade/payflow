# PayFlow demo: token -> seed wallets -> payments with Idempotency-Key -> poll to a terminal state -> print states.
# Needs the stack up (docker compose -f deploy/compose.yaml up -d --build --wait). Loopback only.
$ErrorActionPreference = 'Stop'

$TokenUrl = 'http://localhost:8180/realms/payflow/protocol/openid-connect/token'
$WalletApi = 'http://localhost:8082/api/v1/ops/wallets'
$PaymentApi = 'http://localhost:8081/api/v1/payments'
$ClientId = 'payflow-test'
# LOCAL DEMO VALUE: realm users alice and ops; each password equals the user name. Never reuse anywhere real.
$AlicePassword = 'alice'
$OpsPassword = 'ops'
# Fixed Keycloak user ids from the realm export (wallet owners are the token subject).
$AliceSub = 'e45bda66-092a-4922-b8e0-b14c7b03b479'
$BobSub = 'ffbff25f-7df1-4acc-a432-40ec070024f7'
$TimeoutSeconds = 120

$Run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$Payer = "demo-payer-$Run"
$Payee = "demo-payee-$Run"

function Get-Token([string]$User, [string]$Password) {
    $form = @{ grant_type = 'password'; client_id = $ClientId; username = $User; password = $Password }
    (Invoke-RestMethod -Method Post -Uri $TokenUrl -Body $form).access_token
}

function Add-Wallet([string]$Id, [string]$Owner, [long]$Balance) {
    $body = @{ id = $Id; ownerSub = $Owner; initialBalance = $Balance } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri $WalletApi -ContentType 'application/json' -Body $body `
        -Headers @{ Authorization = "Bearer $OpsToken" } | Out-Null
}

function Send-Payment([string]$Name, [long]$Amount) {
    $body = @{ payer = $Payer; payee = $Payee; amount = $Amount; currency = 'INR' } | ConvertTo-Json
    $headers = @{ Authorization = "Bearer $AliceToken"; 'Idempotency-Key' = "demo-$Run-$Name" }
    (Invoke-RestMethod -Method Post -Uri $PaymentApi -ContentType 'application/json' -Body $body -Headers $headers).id
}

# Polls until COMPLETED, REJECTED or REVERSED; gives up after $TimeoutSeconds.
function Wait-Terminal([string]$Id) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $state = (Invoke-RestMethod -Uri "$PaymentApi/$Id" -Headers @{ Authorization = "Bearer $AliceToken" }).state
        if ($state -in 'COMPLETED', 'REJECTED', 'REVERSED') { return $state }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return "TIMEOUT (last state: $state)"
}

Write-Host 'Getting tokens'
$AliceToken = Get-Token 'alice' $AlicePassword
$OpsToken = Get-Token 'ops' $OpsPassword

Write-Host "Seeding wallets $Payer (100000) and $Payee (5000)"
Add-Wallet $Payer $AliceSub 100000
Add-Wallet $Payee $BobSub 5000

$failed = $false
foreach ($spec in @(@('ok-1', 2500), @('ok-2', 1000), @('too-big', 9000000))) {
    $id = Send-Payment $spec[0] $spec[1]
    $state = Wait-Terminal $id
    if ($state -like 'TIMEOUT*') { $failed = $true }
    Write-Host "payment $($spec[0]) amount=$($spec[1]) id=$id -> $state"
}
if ($failed) { exit 1 }
