# iurikaFlows

A personal app store for Android. It lists every open-source app published on [github.com/Iurika-N-T-R](https://github.com/Iurika-N-T-R),
installs them, and keeps them up to date.

- Each public, non-fork repo whose latest GitHub release has an `.apk` asset appears in the store
- **Découvrir**: search your apps, Install / Update / Open from the list; a banner when iurikaFlows itself has a new version
- **Mises à jour**: pending updates with their release notes, "update all", last check time and a manual check; apps already up to date
- **Installées**: apps installed from the store
- **Réglages**: auto-update of the store and of the apps, Wi-Fi only, pre-releases, check every 1 h / 6 h / 24 h, optional GitHub token (lifts the 60 requests/hour API limit)
- App page: version, size, installed version, release count, Install / Update / Open with a progress bar, what's new and full changelog, description, repo and releases links, per-app auto-update switch, uninstall
- Apps installed through iurikaFlows update silently in the background (Android 12+); iurikaFlows updates itself the same way once its own repo has releases

To publish an app: create a GitHub release on the repo with the APK attached. Tags like `v1.2.3` or `V1.2.3` are compared with the installed `versionName`.

## Build

```
./gradlew assembleDebug
```

Release signing reads `keystore.properties` (git-ignored) at the repo root.

## License

MIT
