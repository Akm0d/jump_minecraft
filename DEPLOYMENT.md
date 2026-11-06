# Alienware Paper deployment and rollback

Read-only inspection on 2026-11-06 found:

- Windows host `DESKTOP-UDACJER`, reached through SSH alias `jump`.
- Actual server directory `C:\Users\admin\jump_minecraft`; the historical
  `C:\JUMP\Minecraft` path does not exist.
- Paper `26.3-231-main@8a170a3`, API `26.3.build.231-stable`.
- Java `C:\Program Files\Java\jdk-25.0.5.0.3\bin\java.exe`.
- Installed plugin `plugins\jump-minecraft-0.1.0.jar`, SHA-256
  `C404419A2370258CFA50693C0E7BF3FFAC7E866A42902C6BB01EAAE849D9E52A`.
- The pre-deployment configuration targeted the historical relay at
  `10.1.10.113:12100`; it is retained in the timestamped rollback backup. The
  installed configuration now contains only multi-station registration,
  per-station staleness, and movement settings.
- No matching Windows service, scheduled task, or root startup script was
  found. The machine's local notes document
  `java -Xms2G -jar .\paper.jar`; validation used that command with `nogui`
  and stopped Paper through its console.
- The 2026-11-05 log showed `jumpsteve` joining from `10.1.10.113`, successful
  plugin load/manual arm, 17,365 rejected datagrams, zero accepted frames, and
  `Expected digit at byte 0`. This is consistent with binary v2 reaching the
  previous v1-only decoder.

Authorized deployment validation was performed on 2026-11-06:

- Backup:
  `C:\Users\admin\jump_minecraft\plugins\backups\20261106-124359`.
  It contains the original JAR, original config, and `ROLLBACK.txt`.
- Installed JAR SHA-256:
  `8EF7A26F84C640DB486D443DFFFADCC9664B8E1984A863C1A1F0AEF729E58AF3`.
- Paper reached ready state twice with JumpMinecraft enabled and UDP 12101
  bound. `jump status` reported no startup or registry errors.
- A temporary, unassigned validation station received the registration
  acknowledgement and byte-exact FLIGHT subscription heartbeat. Paper accepted
  one 368-byte v2 `LANDED` fixture:
  `accepted=1`, `rejected=0`, `lastProtocol=v2`,
  `latest=LANDED #987654321`.
- No player was connected or assigned:
  `player=none`, `armed=false`, `active=false`. No trajectory or physical
  command was sent.
- The obsolete single-station endpoint keys were removed from the installed
  plugin config after preserving the original. Paper restarted successfully
  with the clean config and was stopped through the console.
- Final observed state: no Java process, TCP 25565 listener, or UDP 12101
  listener. The verified JAR remains installed for the coordinated technician
  test.

Prepared local release artifact:

```text
build/libs/jump-minecraft-0.1.0.jar
SHA-256 eba3379e9829b792a112181aef892bb53e8ee5c7c18ba747eb497164c77854a0
```

## Maintenance-window procedure

Do not run this procedure until the release checksum is recorded, the operator
confirms no players are connected, the actual clean stop/start command is
known, and deployment is explicitly authorized.

Stage the release without replacing the live plugin:

```bash
scp build/libs/jump-minecraft-0.1.0.jar \
  'jump:C:/Users/admin/jump_minecraft/plugins/jump-minecraft-0.1.0.jar.new'
```

In a PowerShell SSH session, record state and make timestamped backups:

```powershell
$root = 'C:\Users\admin\jump_minecraft'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $root "plugins\backups\$stamp"
New-Item -ItemType Directory -Force -Path $backup | Out-Null
Copy-Item "$root\plugins\jump-minecraft-0.1.0.jar" $backup
if (Test-Path "$root\plugins\JumpMinecraft\config.yml") {
    Copy-Item "$root\plugins\JumpMinecraft\config.yml" $backup
}
Get-FileHash "$root\plugins\jump-minecraft-0.1.0.jar" -Algorithm SHA256
Get-FileHash "$root\plugins\jump-minecraft-0.1.0.jar.new" -Algorithm SHA256
$backup
```

Use the administrator-confirmed clean stop command. Never use Paper `/reload`.
After the Java process and TCP 25565 listener are confirmed stopped, replace the
plugin:

```powershell
$root = 'C:\Users\admin\jump_minecraft'
Move-Item "$root\plugins\jump-minecraft-0.1.0.jar.new" `
    "$root\plugins\jump-minecraft-0.1.0.jar" -Force
```

Use the administrator-confirmed clean start command, then verify:

```powershell
Get-Process java -ErrorAction SilentlyContinue
Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue
Get-NetUDPEndpoint -LocalPort 12101 -ErrorAction SilentlyContinue
Get-Content 'C:\Users\admin\jump_minecraft\logs\latest.log' -Tail 200
```

The log must show the multi-station bridge enabled in passive v1/v2 mode and no
plugin-load or registry-bind error. In the Paper console, run `plugins`, `/jump
status`, and `/jump stations`. Do not start a synthetic sender or physical
flight during deployment verification.

## Rollback

Use the same confirmed clean stop command. Let `$backup` be the exact directory
printed during backup, then restore both artifacts:

```powershell
$root = 'C:\Users\admin\jump_minecraft'
Copy-Item "$backup\jump-minecraft-0.1.0.jar" `
    "$root\plugins\jump-minecraft-0.1.0.jar" -Force
if (Test-Path "$backup\config.yml") {
    Copy-Item "$backup\config.yml" `
        "$root\plugins\JumpMinecraft\config.yml" -Force
}
Get-FileHash "$root\plugins\jump-minecraft-0.1.0.jar" -Algorithm SHA256
```

Confirm the restored hash equals
`C404419A2370258CFA50693C0E7BF3FFAC7E866A42902C6BB01EAAE849D9E52A`,
then use the confirmed clean start command and inspect `logs\latest.log`.
