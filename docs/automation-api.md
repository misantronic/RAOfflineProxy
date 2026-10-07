# Automation API (Android)

Other apps, such as sleep managers or automation tools, can start, stop and query the proxy through RAOfflineProxy's content provider:

```
content://com.raofflineproxy.config
```

## Setup

Declare the control permission and make the provider visible to your app:

```xml
<uses-permission android:name="com.raofflineproxy.permission.CONTROL_PROXY" />

<queries>
    <provider android:authorities="com.raofflineproxy.config" />
</queries>
```

`status` needs no permission. `start` and `stop` throw a `SecurityException` without it.

::: warning Install order
Android only grants the permission if RAOfflineProxy was installed before your app. If your app was installed first, reinstall it.
:::

## Methods

All methods go through `ContentResolver.call()` and return a `Bundle`. `start` and `stop` block until the work is done, so call them off the main thread.

```kotlin
val uri = Uri.parse("content://com.raofflineproxy.config")
val result = contentResolver.call(uri, "stop", null, null)
val code = result?.getString("result")
val status = result?.getString("status")
```

| Method | Does | Returns |
|---|---|---|
| `start` | Patches the enabled emulators and starts the proxy | `result`, `status` |
| `stop` | Stops the proxy and reverts the emulator patches | `result`, `status` |
| `status` | Nothing | `status` |

`start` and `stop` go through the same logic as the app: emulator patching, restart handling and cleanup work exactly as they do in the UI. They are idempotent, so calling `start` while the proxy is running or `stop` while it's stopped does nothing. Neither changes the **Autostart RAOfflineProxy at startup** setting.

### Result codes

| `result` | Meaning |
|---|---|
| `ok` | Done, or nothing to do |
| `no_emulator_enabled` | No emulator is enabled in RAOfflineProxy |
| `port_unavailable` | Another app uses the proxy port |
| `patch_failed` | An emulator config couldn't be patched, open RAOfflineProxy to fix it |
| `foreground_service_not_allowed` | Android blocked starting the proxy from the background, see below |

### Starting from the background

Since Android 12, an app in the background may not start a foreground service. While RAOfflineProxy is in the background, `start` fails with `foreground_service_not_allowed`. Any emulator patches are rolled back and `shouldBeRunning` stays `false`. Setting RAOfflineProxy's battery usage to **Unrestricted** lifts this limit.

`start` returns `ok` once the service has been asked to start. `running` turns `true` once the proxy accepts connections. If Android refuses the service afterwards, which happens when RAOfflineProxy's battery usage is **Restricted**, the proxy stops again and `shouldBeRunning` turns `false`.

`stop` returns `ok` as soon as the proxy has been asked to stop. `running` turns `false` shortly after, once the service is gone.

## Status

```json
{
  "version": 2,
  "running": true,
  "shouldBeRunning": true,
  "online": true,
  "queue": { "count": 342, "state": "waiting", "nextWindowAt": 1759230000000 },
  "pendingAwards": { "count": 3, "state": "syncing", "error": null }
}
```

| Field | Meaning |
|---|---|
| `version` | `2` since `pendingAwards` was added |
| `running` | The proxy accepts connections |
| `shouldBeRunning` | RAOfflineProxy intends the proxy to run. `true` while `running` is `false` means it's starting or about to restart |
| `online` | RetroAchievements is reachable |
| `queue.count` | Games waiting in the [caching queue](/caching-games) |
| `queue.state` | See below |
| `queue.nextWindowAt` | Epoch milliseconds of the next caching batch while `waiting`, otherwise `null` |
| `pendingAwards.count` | Achievements unlocked offline that haven't been uploaded yet |
| `pendingAwards.state` | See below |
| `pendingAwards.error` | Why the upload failed while `blocked`, otherwise `null` |

| `queue.state` | Meaning |
|---|---|
| `idle` | The queue is empty |
| `caching` | Games are being cached right now |
| `waiting` | Waiting for the next caching window. RAOfflineProxy wakes the device for it |
| `blocked` | Won't progress without the user, e.g. the proxy is stopped or the login is invalid. Treat it like `idle` |

Pending awards are uploaded automatically when the proxy starts and whenever RetroAchievements becomes reachable again. A failed upload isn't retried until the next time that happens.

| `pendingAwards.state` | Meaning |
|---|---|
| `idle` | Nothing to upload |
| `waiting` | Awards will upload once RetroAchievements is reachable |
| `syncing` | Awards are being uploaded right now |
| `blocked` | The proxy is stopped, or the last upload failed and won't be retried until connectivity drops and returns. Treat it like `idle` |

| `pendingAwards.error` | Meaning |
|---|---|
| `auth` | The login was rejected, log in again in RAOfflineProxy |
| `chain_broken` | The pending awards failed their integrity check, open RAOfflineProxy |
| `refresh_failed` | The achievement data couldn't be refreshed from RetroAchievements |
| `upload_failed` | Some awards couldn't be uploaded, e.g. a network or server error |

## Observing changes

RAOfflineProxy notifies the provider URI whenever the status changes, so you can register a `ContentObserver` instead of polling. Android delivers them about 10 seconds late while your app is in the background, and right away while it's active, for example while it runs a foreground service.

```kotlin
contentResolver.registerContentObserver(uri, false, object : ContentObserver(handler) {
    override fun onChange(selfChange: Boolean) {
        val status = contentResolver.call(uri, "status", null, null)?.getString("status")
    }
})
```

## Example: sleep manager

Before sleep:

- `queue.state` is `idle` or `blocked`: call `stop`, start again on wake
- `queue.state` is `caching` or `waiting`: leave the proxy running until the queue is done, or let the user decide

To sync achievements in a maintenance window, turn Wi-Fi on and keep it on until `online` is `true` and `pendingAwards.state` is `idle` or `blocked`.
