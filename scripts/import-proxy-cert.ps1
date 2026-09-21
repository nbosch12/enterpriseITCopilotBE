<#
    Creates a Java truststore that contains the JDK default CAs plus the
    certificate chain presented by your (TLS-intercepting) corporate proxy.

    Usage:
        powershell -ExecutionPolicy Bypass -File .\scripts\import-proxy-cert.ps1
        powershell -ExecutionPolicy Bypass -File .\scripts\import-proxy-cert.ps1 -TargetUrl "https://s3-eu-central-1.amazonaws.com" -ProxyUrl "http://localhost:3128"

    Afterwards start the app with the generated truststore, e.g.:
        $env:TRUST_STORE_PATH="$PWD\certs\proxy-truststore.jks"
        mvn spring-boot:run
#>
param(
    [string]$TargetUrl = "https://s3-eu-central-1.amazonaws.com",
    [string]$ProxyUrl = "http://localhost:3128",
    [string]$OutputDir = (Join-Path $PSScriptRoot "..\certs"),
    [string]$StorePassword = "changeit"
)

$ErrorActionPreference = "Stop"

# ---- locate the JDK -------------------------------------------------------
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\keytool.exe" } else { "keytool" }
if ($env:JAVA_HOME -and -not (Test-Path $keytool)) {
    throw "keytool not found at $keytool. Check JAVA_HOME."
}
$sourceStore = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "lib\security\cacerts" } else { $null }
if (-not $sourceStore -or -not (Test-Path $sourceStore)) {
    throw "Could not find the JDK cacerts file. Set JAVA_HOME to your JDK 21 installation."
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$OutputDir = (Resolve-Path $OutputDir).Path
$trustStore = Join-Path $OutputDir "proxy-truststore.jks"

# ---- grab the certificate chain the proxy presents ------------------------
Write-Host "Fetching certificate chain for $TargetUrl through $ProxyUrl ..."

$script:collected = New-Object System.Collections.ArrayList

[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
$previousCallback = [System.Net.ServicePointManager]::ServerCertificateValidationCallback
[System.Net.ServicePointManager]::ServerCertificateValidationCallback = {
    param($sender, $certificate, $chain, $sslPolicyErrors)
    if ($chain -and $chain.ChainElements.Count -gt 0) {
        foreach ($element in $chain.ChainElements) {
            [void]$script:collected.Add($element.Certificate)
        }
    } elseif ($certificate) {
        [void]$script:collected.Add($certificate)
    }
    return $true
}

try {
    $request = [System.Net.HttpWebRequest]::Create($TargetUrl)
    $request.Proxy = New-Object System.Net.WebProxy($ProxyUrl, $false)
    $request.Timeout = 30000
    $request.Method = "HEAD"
    try {
        $response = $request.GetResponse()
        $response.Close()
    } catch {
        Write-Warning "HTTP request failed ($($_.Exception.Message)) - continuing if certificates were captured."
    }
} finally {
    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = $previousCallback
}

$collected = $script:collected
if ($collected.Count -eq 0) {
    throw "No certificates captured. Is the proxy $ProxyUrl reachable?"
}

# ---- start from a copy of the JDK cacerts ---------------------------------
Copy-Item $sourceStore $trustStore -Force
# a copied cacerts keeps the JDK password
$sourcePassword = "changeit"

$index = 0
foreach ($cert in $collected) {
    $index++
    $cerFile = Join-Path $OutputDir ("proxy-chain-$index.cer")
    [System.IO.File]::WriteAllBytes($cerFile, $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
    $alias = "proxy-ca-$index"
    Write-Host "Importing $($cert.Subject) as alias '$alias'"
    & $keytool -importcert -noprompt -trustcacerts -alias $alias -file $cerFile -keystore $trustStore -storepass $sourcePassword | Out-Null
}

if ($StorePassword -ne $sourcePassword) {
    & $keytool -storepasswd -keystore $trustStore -storepass $sourcePassword -new $StorePassword | Out-Null
}

Write-Host ""
Write-Host "Truststore created: $trustStore"
Write-Host "Run the application with:"
Write-Host "    `$env:TRUST_STORE_PATH=`"$trustStore`""
Write-Host "    `$env:TRUST_STORE_PASSWORD=`"$StorePassword`""
Write-Host "    mvn spring-boot:run"

