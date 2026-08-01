[CmdletBinding()]
param(
    [string]$Profile = "vulnflow-admin",
    [string]$ExpectedAccount = "160172542031",
    [string]$ExpectedRegion = "eu-west-1"
)

$ErrorActionPreference = "Stop"
$identityJson = & aws sts get-caller-identity --profile $Profile --output json 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "AWS profile '$Profile' is unavailable or not logged in. Run aws sso login first."
}

$identity = $identityJson | ConvertFrom-Json
if ($identity.Account -ne $ExpectedAccount) {
    throw "AWS profile '$Profile' targets account '$($identity.Account)', expected '$ExpectedAccount'."
}

$ssoArnPattern = "^arn:aws:sts::$ExpectedAccount`:assumed-role/AWSReservedSSO_VulnFlowTerraformOperator_[^/]+/.+$"
if ($identity.Arn -notmatch $ssoArnPattern) {
    throw "AWS profile '$Profile' is not the expected VulnFlow IAM Identity Center session."
}

$configuredRegion = (& aws configure get region --profile $Profile 2>$null).Trim()
if ($configuredRegion -ne $ExpectedRegion) {
    throw "AWS profile '$Profile' uses region '$configuredRegion', expected '$ExpectedRegion'."
}

Write-Output "Temporary Terraform identity verified."
Write-Output "Account=$($identity.Account)"
Write-Output "Arn=$($identity.Arn)"
Write-Output "Region=$configuredRegion"
