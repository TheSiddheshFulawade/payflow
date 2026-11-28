# REQ-PF-042 measured run on the compose stack (deploy/compose.yaml up first; jar: ./mvnw -DskipTests package -pl reconciliation -am).
# Seeds N finished payments and their holds straight into the payment and wallet databases on a closed business
# date (the job only accepts date <= today - 2, so payments sent through the saga today cannot be reconciled yet),
# then times the real job: HTTP statement download from both services, staging in chunks, matching, report.
# Run: powershell -NoProfile -File deploy/recon-measured/run.ps1 -N 100000 -Day 2026-11-21 -Tag rm100k
# LOCAL DEMO VALUES (realm user ops/ops, recon DB password) - the same ones deploy/demo.ps1 and compose use.
param(
    [int] $N = 10000,
    [Parameter(Mandatory)] [ValidatePattern('^\d{4}-\d{2}-\d{2}$')] [string] $Day,
    # unique per run: payment ids are md5(tag-g), so a second run on the same stack needs a new tag
    [Parameter(Mandatory)] [ValidatePattern('^[a-z0-9-]{1,20}$')] [string] $Tag
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Compose = Join-Path $Root 'deploy\compose.yaml'
$Jar = Join-Path $Root 'reconciliation\target\reconciliation-0.1.0-SNAPSHOT.jar'
$Log = Join-Path $Root "reconciliation\target\measured-run-$Tag.log"

foreach ($db in 'payment', 'wallet') {
    Get-Content (Join-Path $PSScriptRoot "seed-$db.sql") -Raw |
        docker compose -f $Compose exec -T postgres psql -U postgres -d $db -v ON_ERROR_STOP=1 -v n=$N -v day=$Day -v tag=$Tag
    if ($LASTEXITCODE -ne 0) { throw "seeding $db failed" }
}

$form = @{ grant_type = 'password'; client_id = 'payflow-test'; username = 'ops'; password = 'ops' }
$env:RECON_OPS_TOKEN = (Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:8180/realms/payflow/protocol/openid-connect/token' -Body $form).access_token
$env:RECON_DB_PASSWORD = 'recon' # LOCAL DEMO VALUE

$sw = [Diagnostics.Stopwatch]::StartNew()
try {
    & java -jar $Jar "businessDate=$Day" *> $Log
    $rc = $LASTEXITCODE
} finally {
    $sw.Stop()
    Remove-Item Env:RECON_OPS_TOKEN, Env:RECON_DB_PASSWORD
}

Write-Host ("exit={0} wall={1:N1}s (includes JVM start)" -f $rc, $sw.Elapsed.TotalSeconds)
Select-String -Path $Log -Pattern 'Step: \[|status: \[' | ForEach-Object { $_.Line -replace '^.*(Step:|Job:)', '$1' }
docker compose -f $Compose exec -T postgres psql -U postgres -d recon -At -c `
    "select outcome, count(*) from recon_result where business_date = '$Day' group by 1 order by 1"
