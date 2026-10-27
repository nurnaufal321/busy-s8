# API coverage

Status of every operation in the pinned OpenAPI spec (`vendor/openapi.yaml`, v27.14.0).
`implemented` = real behaviour, `stubbed` = schema-valid fixed response, `missing` = not served yet.

Also served: `GET /openapi.yaml` (the merged spec, as the firmware does).

| Tag | Method | Path | operationId | Status | Phase |
|---|---|---|---|---|---|
| Account | DELETE | `/api/account` | unlinkAccount | missing | 3 |
| Account | GET | `/api/account/backend` | getAccountBackend | missing | 3 |
| Account | PUT | `/api/account/backend` | setAccountBackend | missing | 3 |
| Account | GET | `/api/account/info` | getAccountInfo | missing | 3 |
| Account | POST | `/api/account/link` | linkAccount | missing | 3 |
| Account | GET | `/api/account/status` | getAccountStatus | missing | 3 |
| Apps | DELETE | `/api/apps` | removeApp | missing | 4 |
| Apps | POST | `/api/apps/install` | installApp | missing | 4 |
| Apps | POST | `/api/apps/launch` | launchApp | missing | 4 |
| Apps | GET | `/api/apps/list` | listApps | missing | 4 |
| Apps | POST | `/api/apps/quit` | quitApp | missing | 4 |
| Apps | DELETE | `/api/apps/settings` | resetAppSettings | missing | 4 |
| Apps | GET | `/api/apps/settings` | getAppSettings | missing | 4 |
| Apps | PUT | `/api/apps/settings` | setAppSettings | missing | 4 |
| Apps | POST | `/api/apps/stage` | stageApp | missing | 4 |
| Assets | DELETE | `/api/assets/upload` | deleteAppAssets | missing | 3 |
| Assets | POST | `/api/assets/upload` | uploadAssetWithAppId | missing | 3 |
| Assets | DELETE | `/api/audio/play` | stopAudio | missing | 3 |
| Assets | POST | `/api/audio/play` | playAudio | missing | 3 |
| Assets | DELETE | `/api/display/draw` | clearDisplay | implemented | 1 |
| Assets | POST | `/api/display/draw` | drawOnDisplay | implemented | 1 |
| BLE | POST | `/api/ble/disable` |  | missing | 3 |
| BLE | POST | `/api/ble/enable` |  | missing | 3 |
| BLE | DELETE | `/api/ble/pairing` |  | missing | 3 |
| BLE | GET | `/api/ble/status` |  | missing | 3 |
| Busy | GET | `/api/busy/profiles/{slot}` | getBusyProfile | missing | 2 |
| Busy | PUT | `/api/busy/profiles/{slot}` | setBusyProfile | missing | 2 |
| Busy | GET | `/api/busy/snapshot` | getBusySnapshot | missing | 2 |
| Busy | PUT | `/api/busy/snapshot` | setBusySnapshot | missing | 2 |
| Input | POST | `/api/input` | setInputKey | missing | 2 |
| Settings | GET | `/api/access` | getHttpAccess | missing | 3 |
| Settings | POST | `/api/access` | setHttpAccess | missing | 3 |
| Settings | DELETE | `/api/access/tokens` | deleteAllAccessTokens | missing | 3 |
| Settings | GET | `/api/access/tokens` | getAccessTokens | missing | 3 |
| Settings | POST | `/api/access/tokens` | createAccessToken | missing | 3 |
| Settings | DELETE | `/api/access/tokens/{short_id}` | revokeAccessToken | missing | 3 |
| Settings | GET | `/api/audio/volume` | getAudioVolume | missing | 3 |
| Settings | POST | `/api/audio/volume` | setAudioVolume | missing | 3 |
| Settings | GET | `/api/display/brightness` | getDisplayBrightness | missing | 3 |
| Settings | POST | `/api/display/brightness` | setDisplayBrightness | missing | 3 |
| Settings | GET | `/api/name` |  | missing | 3 |
| Settings | POST | `/api/name` |  | missing | 3 |
| Smart Home | DELETE | `/api/smart_home/pairing` |  | missing | 3 |
| Smart Home | GET | `/api/smart_home/pairing` | getSmartHomeCommissioningStatus | missing | 3 |
| Smart Home | POST | `/api/smart_home/pairing` | startSmartHomePairing | missing | 3 |
| Smart Home | GET | `/api/smart_home/switch` |  | missing | 3 |
| Smart Home | POST | `/api/smart_home/switch` |  | missing | 3 |
| Storage | GET | `/api/storage/list` | listStorageFiles | missing | 3 |
| Storage | POST | `/api/storage/mkdir` | createStorageDir | missing | 3 |
| Storage | GET | `/api/storage/read` | readStorageFile | missing | 3 |
| Storage | DELETE | `/api/storage/remove` | removeStorageFile | missing | 3 |
| Storage | POST | `/api/storage/rename` | RenameStorageFile | missing | 3 |
| Storage | GET | `/api/storage/status` | getStorageStatus | missing | 3 |
| Storage | POST | `/api/storage/write` | writeStorageFile | missing | 3 |
| Streaming | GET | `/api/screen` |  | missing | 3 |
| Streaming | GET | `/api/status/ws` | connectWebSocket | missing | 3 |
| System | POST | `/api/log_dump` | dumpLog | missing | 3 |
| System | GET | `/api/status` | getStatus | missing | 3 |
| System | GET | `/api/status/device` | getStatusDevice | missing | 3 |
| System | GET | `/api/status/firmware` | getStatusFirmware | missing | 3 |
| System | GET | `/api/status/power` | getStatusPower | missing | 3 |
| System | GET | `/api/status/system` | getStatusSystem | missing | 3 |
| System | GET | `/api/transport` | getTransport | missing | 3 |
| System | GET | `/api/version` | getVersion | missing | 3 |
| Time | GET | `/api/time` | getTime | missing | 3 |
| Time | POST | `/api/time/timestamp` | setTimeTimestamp | missing | 3 |
| Time | GET | `/api/time/timezone` | getTimeTimezone | missing | 3 |
| Time | POST | `/api/time/timezone` | setTimeTimezone | missing | 3 |
| Time | GET | `/api/time/tzlist` | getTimeTzlist | missing | 3 |
| Updater | POST | `/api/update` | updateFirmware | missing | 3 |
| Updater | POST | `/api/update/abort_download` | abortFirmwareDownload | missing | 3 |
| Updater | GET | `/api/update/autoupdate` | getAutoupdateSettings | missing | 3 |
| Updater | POST | `/api/update/autoupdate` | setAutoupdateSettings | missing | 3 |
| Updater | GET | `/api/update/changelog` | getUpdateChangelog | missing | 3 |
| Updater | POST | `/api/update/check` | checkFirmwareUpdate | missing | 3 |
| Updater | POST | `/api/update/install` | installFirmwareUpdate | missing | 3 |
| Updater | GET | `/api/update/status` | getFirmwareUpdateStatus | missing | 3 |
| Wi-Fi | POST | `/api/wifi/connect` |  | missing | 3 |
| Wi-Fi | POST | `/api/wifi/disconnect` |  | missing | 3 |
| Wi-Fi | GET | `/api/wifi/networks` | getWifiNetworks | missing | 3 |
| Wi-Fi | GET | `/api/wifi/status` |  | missing | 3 |

2 of 81 operations implemented.
