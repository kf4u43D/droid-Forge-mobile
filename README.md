# droid Forge mobile

Android port of DROID Forge 1.7.1 for firmware **blue-7**. Version **0.5.3**. Android 8.0+, ARM64; optimized for Galaxy Tab S10 FE+.

Check [GitHub Releases](https://github.com/kf4u43D/droid-Forge-mobile/releases) for published downloads.

## Build

JDK 17, Android SDK 36, NDK 28.2.13676358 and CMake 3.22.1:

```powershell
.\gradlew.bat :app:assembleTabletPreview :app:lintTabletPreview
```

[Menus](docs/MENUS.md) · [Validation](docs/VALIDATION-0.5.3.md).

Original DROID Forge by **Mathias Kettner**, aka **Der Mann mit der Maschine**. Android port by **Dr Delos**. **GPL-3.0**: [LICENSE](LICENSE), [graphics permission](resources/official/GRAPHICS-LICENSE.txt).
