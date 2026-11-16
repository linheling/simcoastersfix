# Minimal Minecraft RCON client — used to drive the dev server during probe runs.
#
# Why this exists: the moddev `runServer` task does not forward stdin, so the console is
# unreachable. RCON is the only way to send commands to it from a script.
#
# Usage:
#   pwsh -File tools/rcon.ps1 -Command "list"
#   pwsh -File tools/rcon.ps1 -CommandFile commands.txt      # one command per line, '#' comments

param(
    [string]$Command,
    [string]$CommandFile,
    [string]$ServerHost = '127.0.0.1',
    [int]$Port = 25575,
    [string]$Password = 'probe',
    [int]$TimeoutMs = 4000
)

$ErrorActionPreference = 'Stop'

function Read-Exactly {
    param([System.IO.Stream]$Stream, [byte[]]$Buffer, [int]$Count)
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($Buffer, $offset, $Count - $offset)
        if ($read -le 0) { throw "connection closed while reading packet" }
        $offset += $read
    }
}

function Receive-Packet {
    param([System.IO.Stream]$Stream)
    $head = New-Object byte[] 4
    Read-Exactly -Stream $Stream -Buffer $head -Count 4
    $length = [BitConverter]::ToInt32($head, 0)
    if ($length -lt 10 -or $length -gt 65536) { throw "bad rcon packet length $length" }
    $rest = New-Object byte[] $length
    Read-Exactly -Stream $Stream -Buffer $rest -Count $length
    return @{
        Id   = [BitConverter]::ToInt32($rest, 0)
        Type = [BitConverter]::ToInt32($rest, 4)
        Body = [System.Text.Encoding]::UTF8.GetString($rest, 8, $length - 10)
    }
}

function Send-Packet {
    param([System.IO.Stream]$Stream, [int]$Id, [int]$Type, [string]$Body)
    $payload = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $length = 4 + 4 + $payload.Length + 2
    $buffer = New-Object byte[] (4 + $length)
    [BitConverter]::GetBytes([int]$length).CopyTo($buffer, 0)
    [BitConverter]::GetBytes([int]$Id).CopyTo($buffer, 4)
    [BitConverter]::GetBytes([int]$Type).CopyTo($buffer, 8)
    $payload.CopyTo($buffer, 12)
    $Stream.Write($buffer, 0, $buffer.Length)
    $Stream.Flush()
}

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($ServerHost, $Port)
$stream = $client.GetStream()
$stream.ReadTimeout = $TimeoutMs

try {
    Send-Packet -Stream $stream -Id 1 -Type 3 -Body $Password
    $auth = Receive-Packet -Stream $stream
    if ($auth.Id -eq -1) { throw "rcon auth failed (wrong rcon.password?)" }

    $commands = @()
    if ($CommandFile) {
        $commands = Get-Content -Path $CommandFile -Encoding utf8 |
            Where-Object { $_ -notmatch '^\s*(#|$)' } |
            ForEach-Object { $_.Trim() }
    } else {
        $commands = @($Command)
    }

    $nextId = 2
    foreach ($cmd in $commands) {
        Send-Packet -Stream $stream -Id $nextId -Type 2 -Body $cmd
        $nextId++
        $response = ''
        while ($true) {
            try {
                $packet = Receive-Packet -Stream $stream
            } catch [System.IO.IOException] {
                break   # read timeout: server had nothing more to say
            }
            if ($packet.Type -eq 0) {
                if ($packet.Body) { $response += $packet.Body + "`n" }
                else { break }
            }
        }
        Write-Output ("> " + $cmd)
        if ($response.Trim()) { Write-Output ($response.TrimEnd()) }
    }
} finally {
    $stream.Close()
    $client.Close()
}
