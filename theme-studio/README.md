# Y2ThemeStudio

Y2ThemeStudio is the local-first visual authoring app for Y2Player skin format
version 2. It shares the production schema, preview fixtures, and bundled
templates with the Android player.

## Development

Install dependencies and run the development server:

    cd theme-studio
    npm install
    npm run dev

Production checks:

    npm run typecheck
    npm test
    npm run build
    npm run test:e2e

The production build is a static PWA in `dist/`. No server or account is
required. Projects autosave in the browser. Import accepts `skin.json`, theme
folders, and ZIP archives; export produces an installable theme ZIP that users
extract into `Y2Player/Skins`.

## Contract

- `../docs/skins/skin-v2.schema.json` defines structural validation.
- `src/engine/validator.ts` mirrors semantic player restrictions.
- `src/engine/compiler.ts` expands shared styles and typed components.
- `src/engine/geometry.ts` consumes the same fixtures as Android.
- The Android device probe remains the final authority for font rasterization.
