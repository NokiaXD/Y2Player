# Skin preview contract tools

`geometry.mjs` works in browsers and Node without dependencies. It mirrors the
pure Kotlin layout and text-wrapping contract. Run `node tools/skin-preview/check.mjs`
from the repository root. Both runners read `docs/skins/preview-fixtures.json`.
Text measurement is injected; fixtures use code-point widths to test wrapping
independently of platform fonts. They do not claim pixel-identical font rendering.

## Android device probe

Build/install the debug APK (a separate package from release), then:

```sh
adb shell mkdir -p /sdcard/Android/data/com.schulzcode.y2player.debug/files/skin-probe
adb push docs/skins/example-v2/skin.json /sdcard/Android/data/com.schulzcode.y2player.debug/files/skin-probe/skin.json
adb shell am startservice -n com.schulzcode.y2player.debug/com.schulzcode.y2player.debug.SkinPreviewService
adb pull /sdcard/Android/data/com.schulzcode.y2player.debug/files/skin-probe /tmp/y2-skin-probe
```

Wait for `report.json` before pulling results. It must contain `passed: true`.
The service produces Now Playing and Search PNGs and exercises touch across a
redraw, wheel/confirm focus, circular seeking, native Search navigation, and
all five main layouts in the three bundled version 2 skins. The service only
uses synthetic state. It is absent from release builds and requires the DUMP
permission so ordinary apps cannot invoke it.
