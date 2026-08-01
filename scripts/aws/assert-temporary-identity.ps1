[CmdletBinding()]
param(
    [string]$Profile = "vulnflow-admin",
    [string]$ExpectedAccount = "160172542031",
    [string]$ExpectedRegion = "eu-west-1",
    [string]$ExpectedRoleName = "VulnFlowTerraformOperator"
)

$ErrorActionPreference = "Stop"
$identityJson = & aws sts get-caller-identity --profile $Profile --output json 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "AWS profile '$Profile' is unavailable or could not assume the expected temporary role."
}

$identity = $identityJson | ConvertFrom-Json
if ($identity.Account -ne $ExpectedAccount) {
    throw "AWS profile '$Profile' targets account '$($identity.Account)', expected '$ExpectedAccount'."
}

$roleArnPattern = "^arn:aws:sts::$ExpectedAccount`:assumed-role/$([regex]::Escape($ExpectedRoleName))/[^/]+$"
if ($identity.Arn -notmatch $roleArnPattern) {
    throw "AWS profile '$Profile' is not an assumed-role session for '$ExpectedRoleName'."
}

$configuredRegion = (& aws configure get region --profile $Profile 2>$null).Trim()
if ($configuredRegion -ne $ExpectedRegion) {
    throw "AWS profile '$Profile' uses region '$configuredRegion', expected '$ExpectedRegion'."
}

Write-Output "Temporary Terraform identity verified."
Write-Output "Account=$($identity.Account)"
Write-Output "Arn=$($identity.Arn)"
Write-Output "Region=$configuredRegion"
