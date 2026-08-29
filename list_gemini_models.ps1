param(
    [Parameter(Mandatory=$true)]
    [string]$ApiKey
)

$url = "https://generativelanguage.googleapis.com/v1beta/models?key=$ApiKey"

Write-Host "Querying Google Gemini Models endpoint..." -ForegroundColor Cyan

try {
    $response = Invoke-RestMethod -Uri $url -Method Get
    Write-Host "`n=== MODELS AVAILABLE FOR YOUR KEY ===" -ForegroundColor Green
    
    $results = $response.models | ForEach-Object {
        [PSCustomObject]@{
            ModelName = $_.name
            DisplayName = $_.displayName
            Methods = ($_.supportedGenerationMethods -join ", ")
        }
    }
    
    $results | Format-Table -Wrap -AutoSize
    
    Write-Host "`n=== MODELS SUPPORTING BIDI / LIVE WEBSOCKET ===" -ForegroundColor Yellow
    $bidiModels = $results | Where-Object { $_.Methods -match "bidi" -or $_.Methods -match "live" }
    if ($bidiModels) {
        $bidiModels | Format-Table -Wrap -AutoSize
    } else {
        Write-Host "No models explicitly listed 'bidiGenerateContent' in supportedGenerationMethods." -ForegroundColor Gray
    }
} catch {
    Write-Host "Request Failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails.Message) {
        Write-Host "Details: $($_.ErrorDetails.Message)" -ForegroundColor DarkRed
    }
}
