# Milestone 2 service lifecycle

`InferenceService` owns one `EngineManager` and one `GemmaInferenceEngine`.
Opening the Activity only binds an unloaded service. Load model or Run starts
the foreground service; it promotes itself immediately before worker tasks.

```text
UNLOADED -- Load model / Run --> LOADING -- success --> READY
READY -- Run --> GENERATING -- success / cancellation / error --> READY
LOADING / READY / GENERATING -- Unload / Stop --> STOPPING --> UNLOADED
```

A failed initialization returns to UNLOADED with diagnostics and stops the
foreground service. The backend is always explicitly Google Tensor NPU;
there is no CPU retry. A loaded engine has not yet verified generation until
its first successful request.

Load, generation, and model destruction use one executor. A separate executor
can call the real upstream cancellation API while generation is blocked.
Unload waits for both paths to finish. Commands and state/listener delivery
use the Android main thread; heavy inference and descriptor/model operations
do not. The wake lock covers native work, including cleanup, and has a maximum
ten-minute acquisition timeout.

The JNI/C boundary uses opaque engine IDs backed by synchronized shared
ownership. Request IDs prevent stale cancellation from affecting a subsequent
request. Native startup/drain synchronization keeps cancellation from racing
conversation creation or destruction. Every request gets fresh conversation
history/KV session state while the engine/model assets remain resident.

The Android notification provides open, cancel (during generation), and
Unload / Stop actions. Closing or backgrounding the Activity does not unload
the foreground service. Android can still kill the process; a later app launch
is explicitly unloaded rather than automatically loading weights. There is
no boot receiver, HTTP listener, network permission, or server implementation.

Initialization itself cannot be interrupted through the pinned upstream API.
Stopping during loading waits for its return and skips a pending generation.
Generation cancellation is cooperative and waits for the active native tasks
to finish. This is a lifecycle boundary, not permission to free live callbacks.

Android references inspected:

- [Starting/promoting foreground services](https://developer.android.com/develop/background-work/services/fgs/launch)
- [Special-use type and manifest property](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
- [Notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Started and bound service lifecycle](https://developer.android.com/develop/background-work/services/bound-services)

All physical checks passed on the Pixel 10 / GrapheneOS on 2026-10-08:
load/notification, repeated requests without another engine initialization,
generation while the Activity was unbound/backgrounded, cancellation then
successful reuse, stopping during initialization and generation, notification
Unload / Stop, complete descriptor/mapping release, and subsequent reload.
The loaded idle engine held no inference wake lock. Six requests returned real
Gemma text; three additional instrumentation tests exercised the manager with
a fake backend. Measurements and native evidence are retained in
[the device verification record](milestone-verification.md#milestone-2--passed-2026-10-08).
