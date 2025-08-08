# PowerShell script to remove comments from Kotlin files
param(
    [string]$FilePath
)

function Remove-KotlinComments {
    param([string]$InputFile)
    
    Write-Host "Processing: $InputFile"
    
    # Read all lines
    $lines = Get-Content $InputFile
    $cleanLines = @()
    $inMultiLineComment = $false
    
    foreach ($line in $lines) {
        $cleanLine = $line
        
        # Handle multi-line comments /* */
        if ($inMultiLineComment) {
            if ($cleanLine -match '\*/') {
                # End of multi-line comment found
                $cleanLine = $cleanLine -replace '^.*?\*/', ''
                $inMultiLineComment = $false
            } else {
                # Still in multi-line comment, skip entire line
                continue
            }
        }
        
        # Check for start of multi-line comment
        if ($cleanLine -match '/\*') {
            if ($cleanLine -match '/\*.*?\*/') {
                # Single-line /* */ comment
                $cleanLine = $cleanLine -replace '/\*.*?\*/', ''
            } else {
                # Start of multi-line comment
                $cleanLine = $cleanLine -replace '/\*.*$', ''
                $inMultiLineComment = $true
            }
        }
        
        # Remove single-line comments // but be careful with strings and URLs
        # Simple regex that handles most cases
        $cleanLine = $cleanLine -replace '//.*$', ''
        
        # Trim whitespace from end
        $cleanLine = $cleanLine.TrimEnd()
        
        # Only add non-empty lines or preserve empty lines that were originally empty
        if ($cleanLine.Length -gt 0 -or $line.Trim().Length -eq 0) {
            $cleanLines += $cleanLine
        }
    }
    
    # Write back to file
    $cleanLines | Out-File -FilePath $InputFile -Encoding UTF8
    Write-Host "Completed: $InputFile"
}

if ($FilePath) {
    Remove-KotlinComments -InputFile $FilePath
} else {
    # Process all Kotlin files in the project
    $kotlinFiles = Get-ChildItem -Path "app\src" -Filter "*.kt" -Recurse | Where-Object { 
        $_.FullName -notlike "*_with_comments*" -and 
        $_.FullName -notlike "*test*" -and 
        $_.FullName -notlike "*Test*" 
    }
    
    foreach ($file in $kotlinFiles) {
        Remove-KotlinComments -InputFile $file.FullName
    }
    
    Write-Host "All Kotlin files processed!"
}
