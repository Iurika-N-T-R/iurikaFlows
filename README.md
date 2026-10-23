# iurikaflows

A personal app store for Android. It lists every open-source app published on [github.com/Iurika-N-T-R](https://github.com/Iurika-N-T-R),
installs them, and keeps them up to date.

- Each public, non-fork repo whose latest GitHub release has an `.apk` asset appears in the store
- Install / update / open, with release notes on tap
- Background check every 6 hours on Wi-Fi; apps installed through iurikaflows update silently (Android 12+)
- iurikaflows updates itself the same way once its own repo has releases

To publish an app: create a GitHub release on the repo with the APK attached. Tags like `v1.2.3` or `V1.2.3` are compared with the installed `versionName`.

## Build

```
./gradlew assembleDebug
```

Release signing reads `keystore.properties` (git-ignored) at the repo root.

## License

MIT
