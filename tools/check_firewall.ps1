# Check firewall rules related to the app
$rules = Get-NetFirewallRule
foreach ($rule in $rules) {
    if ($rule.DisplayName -match 'zc-core|AndroidDex|androiddex|quic|cargo|rust') {
        Write-Output "$($rule.DisplayName) | Enabled=$($rule.Enabled) | Direction=$($rule.Direction) | Action=$($rule.Action)"
    }
}

Write-Output ""
Write-Output "--- Checking if Windows Firewall is blocking UDP on RNDIS ---"

# Check the network profile for the RNDIS adapter
$adapter = Get-NetConnectionProfile -InterfaceAlias 'Ethernet 3' -ErrorAction SilentlyContinue
if ($adapter) {
    Write-Output "RNDIS Network Category: $($adapter.NetworkCategory)"
    Write-Output "RNDIS Profile Name: $($adapter.Name)"
} else {
    Write-Output "Could not get RNDIS adapter profile"
}

Write-Output ""
Write-Output "--- Firewall profile status ---"
Get-NetFirewallProfile | Select-Object Name, Enabled, DefaultInboundAction, DefaultOutboundAction | Format-Table -AutoSize

Write-Output ""
Write-Output "--- Testing UDP reachability to phone on port 4433 ---"
try {
    $udp = New-Object System.Net.Sockets.UdpClient
    $udp.Client.ReceiveTimeout = 2000
    $bytes = [System.Text.Encoding]::ASCII.GetBytes("test")
    $udp.Send($bytes, $bytes.Length, "10.14.154.91", 4433)
    Write-Output "UDP packet sent to 10.14.154.91:4433"
    $udp.Close()
} catch {
    Write-Output "UDP send failed: $_"
}

Write-Output ""
Write-Output "NOTE: the phone is the QUIC listener on UDP 4433; the Windows receiver only"
Write-Output "      sends outbound, so no INBOUND firewall rule is needed on the PC. The"
Write-Output "      receiver discovers the phone's tethered IP and connects to it."