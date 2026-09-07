# Y2Player skin authoring

Use format version 2 for new skins. Its complete reference, JSON Schema, and
installable Engine Lab example live in `docs/skins`. The version 1 reference
below remains available for compatibility with existing community packs.

Y2Player renders community skins from JSON and local assets. All bundled skins
are editable version 2 examples. Neon Grid is a cyan-and-magenta cyber HUD with
animated focus states and circular seeking. Pixel Garden uses vector flowers,
soft gradients, rounded cards, and botanical playback controls. Studio Deck is
a graphite mixing console with meters, a channel carousel, platter artwork, and
illuminated transport controls. They require no downloaded images or fonts.
Classic and Classic Light retain the original renderer as recovery options.

## Install and select

1. Copy `neon-grid` to `Y2Player/Skins/my-skin` on internal storage or the SD card.
2. Change its `id`, `name`, and `author`. IDs must be unique; `classic`,
   `classic-light`, and all bundled skin IDs (`neon-grid`, `pixel-garden`, `studio-deck`) cannot be replaced.
3. Open **Settings → Interface → Display → Skin → Reload Skins**.
4. Select your skin. Edit the files and reload again to see changes without
   restarting. Loading happens on a worker; selection and playback continue.

Share the folder (optionally zipped for transport). Extract archives before
installing; the player loads folders, not ZIP files. The selected ID is included
in preference backups; skin assets are separate and must also be copied.
A missing or invalid selected skin displays Classic. Safe mode also uses Classic.
Invalid folders appear in the skin picker with a reason; select an error to
show its message. Correct the file and reload. Keep a working copy of your skin.

## Version 1 compatibility reference

For version 2 fields and examples, see
`docs/skins/FORMAT_V2.md` and `docs/skins/skin-v2.schema.json`.

### File structure

```text
my-skin/
  skin.json
  images/background.png       # optional
  images/play.png             # optional
  fonts/my-font.ttf            # optional, use fonts you may distribute
```

The top-level fields are `formatVersion` (1), `id`, `name`, `author`, `viewport`,
`font`, `colors`, `components`, and `screens`. `viewport: [480, 360]` matches the
Y2 display. Other viewports scale uniformly and letterbox rather than stretching.
Drawing and touch targets use the same logical coordinates. The wheel follows
row order from left to right, then top to bottom, paging to keep focus visible.
Hardware actions remain owned by Y2Player's reducer.

`font` accepts `sans`, `sans-serif`, `serif`, `monospace`, or a bundled font path.
Images are Android-decodable raster files such as PNG, JPEG, or WebP. Asset paths
are relative to the skin folder; absolute paths, traversal, and escaping symlinks
are rejected. Images and fonts load once on reload, never while drawing.

`colors` maps names to `#RRGGBB` or `#AARRGGBB` values. Required names:
`background`, `surface`, `primaryText`, `secondaryText`, `accent`, `focusSurface`,
and `warning`. Nodes can reference any named color or use a hex literal.

### Screens and components

`screens` maps stable screen codes to arrays of nodes. A `default` layout is
required and handles all screens without an override, including settings,
library lists, confirmation prompts, and playback option menus.

Provide `main_menu`, `now_playing`, `search`, and `fm_radio` for specialized
layouts, as Neon Grid does. Now Playing and FM have no rows, so they need data
bindings and controls. Search needs a `keyboard` node and a `rows` node to show
results. Other screen codes are defined in `core/state/Screen.kt` in the source.

`components` maps names to reusable node arrays. A component instance places
its children at its origin and clips them to its bounds. Children use local
coordinates, not percentages; component instances do not scale their contents.
All nodes draw in array order, with later nodes above earlier nodes. Hit testing
uses the topmost actionable node. Nodes are clipped to their parent and viewport.

```json
{
  "type": "component",
  "bounds": [12, 48, 456, 48],
  "asset": "row"
}
```

### Nodes

Every node has `type` and `bounds: [x, y, width, height]`. Optional common fields:

| Field | Meaning / default |
| --- | --- |
| `color` | Foreground color; `primaryText` |
| `background` | Image placeholder or progress track; `surface` |
| `size` | Text size in logical units; 16 |
| `bold` | Bold font; false |
| `align` | `left`, `center`, or `right`; left |
| `lines` | Maximum wrapped text lines, 1–12; 1. Overflow is ellipsized. |
| `radius` | Rectangle/progress corner radius; 0 |
| `stroke` | Rectangle outline width; 0 means filled |
| `when` | Visibility condition; `always` |
| `action` | Touch action; absent means no action |

Node types:

- `rect`: filled or outlined rectangle.
- `text`: literal text with `{binding}` substitutions.
- `image`: bundled image identified by `asset`, stretched to its bounds.
- `artwork`: current track artwork; placeholder when absent. Stretched to bounds,
  so use square bounds to retain square album artwork.
- `icon`: built-in icon by enum name in `asset`, or the current row's semantic
  icon when `asset` is omitted. Use `image` for replacement icon artwork.
- `progress`: playback position; `segments: 0` is continuous, 1–128 is segmented.
  `gap` controls segment spacing.
- `group`: places and clips `children` relative to its bounds.
- `component`: places a reusable component named by `asset`.
- `rows`: repeats `children` for the current screen's rows. `columns` defaults to
  1 (maximum 8), `rowHeight` to 48, and `gap` to 4. A row tap selects; tapping the
  selected row confirms. Wheel focus and touch use the same paging geometry.
  Child bounds are local to each cell. Nested row repeaters are unsupported.
- `keyboard`: search keyboard; bounds and text size are configurable, keys and
  focus behavior follow Y2Player's search state.

### Data and state

Bindings: `screen.title`, `track.title`, `track.artist`, `track.album`,
`playback.elapsed`, `playback.duration`, `playback.status`, `playback.details`
(year and genre when Extra Track Info is enabled), `battery`, `position`,
`search.query`, `fm.frequency`, `fm.status`, `message`, `alphabet`, `empty.message`.
Within rows: `row.title`, `row.subtitle`, `row.number`, `row.trailing` (duration).

Visibility conditions: `always`, `focused`, `unfocused`, `active`, `unavailable`,
`playing`, `paused`, `hasTrack`, `noTrack`, `message`, `alphabet`, `emptyRows`.
`paused` means any state other than playing. `active` follows Y2Player's row
semantics (e.g. current track, enabled option, or multi-selected item).

Actions: `confirm`, `back`, `home`, `nowPlaying`, `playPause`, `next`, `previous`,
`left`, `right`. These dispatch existing application actions; they cannot run
code or access files. `next` and `previous` seek stations on the FM screen.
Search retains its existing transport locks.

Include message and alphabet components in each screen, plus an empty-state
label in list layouts. Neon Grid demonstrates all three. Playback options may
be presented as lists rather than the Classic radial menu.

### Validation and limits

Unknown fields, node types, bindings, colors, conditions, and actions produce
errors. Component references must exist and be acyclic. A pack is parsed and
its assets validated before it is published; a bad pack cannot partially apply.

- Manifest: 256 KiB; 512 source nodes; 12 child levels; 16 expanded levels.
- Expanded screen: at most 4096 nodes including visible row instances.
- Viewport: 240–1920 units per dimension; numeric values must be finite.
- Text: 1024 characters per template; name/author: 64 characters.
- Images: 2 MiB per encoded file, 1024×1024 maximum, 8 MiB decoded per pack,
  24 MiB across loaded packs. Fonts: 2 MiB per external font.
- At most 32 external folders, sorted within each storage root. Duplicate IDs
  are rejected; bundled skins take precedence.

Version 1 provides static scene layouts, state-dependent styling, and live
progress updates. It does not execute scripts, animate transitions, reprogram
hardware keys, or embed Android/Web views. Classic is a retained native fallback,
not yet an exported declarative skin. A desktop preview editor and ZIP import
are not included.

## Developer checks

From the repository root:

```sh
ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug
```

`SkinEngineTest` and `SkinV2Test` parse every bundled skin, check screen
coverage and v2 focus controls, exercise
layout/hit-test paging, reject invalid packs, and verify selection and backup
migration. The host JSON dependency is test-only; Android's JSON implementation
is used in the app. Verify new layouts on the device, especially long titles,
empty libraries, search, confirmation prompts, and the optional seventh FM item
on the home menu.
