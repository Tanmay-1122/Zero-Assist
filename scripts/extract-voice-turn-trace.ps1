param(
    [string] $Path,
    [switch] $Csv
)

$pattern = 'id=(?<id>\S+)\s+source=(?<source>\S+)\s+event=(?<event>\S+)\s+elapsedMs=(?<elapsed>\d+)(?:\s+detail=(?<detail>.*))?$'

if ($Path) {
    $lines = Get-Content -LiteralPath $Path
} else {
    $lines = [Console]::In.ReadToEnd() -split "`r?`n"
}

$events = foreach ($line in $lines) {
    if ($line -notmatch 'VoiceTurnTrace') {
        continue
    }
    if ($line -match $pattern) {
        [pscustomobject]@{
            Id = $Matches.id
            Source = $Matches.source
            Event = $Matches.event
            ElapsedMs = [long] $Matches.elapsed
            Detail = $Matches.detail
        }
    }
}

$lastElapsedById = @{}
$waterfall = foreach ($event in ($events | Sort-Object Id, ElapsedMs, Event)) {
    $previous = $lastElapsedById[$event.Id]
    $delta =
        if ($null -eq $previous) {
            0
        } else {
            $event.ElapsedMs - [long] $previous
        }
    $lastElapsedById[$event.Id] = $event.ElapsedMs
    [pscustomobject]@{
        Id = $event.Id
        Source = $event.Source
        Event = $event.Event
        ElapsedMs = $event.ElapsedMs
        DeltaMs = $delta
        Detail = $event.Detail
    }
}

if ($Csv) {
    $waterfall | ConvertTo-Csv -NoTypeInformation
} else {
    $waterfall | Format-Table -AutoSize
}
