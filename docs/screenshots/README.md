# Screenshots

Images used by the README files. They are referenced from all eight of them, so
the paths must stay exactly as they are.

| File | Screen |
|---|---|
| `01-search-light.jpg` | Search results, light theme |
| `02-search-dark.jpg` | Search results with the Tracks/Videos switch, dark theme |
| `03-player-dark.jpg` | Now playing screen |
| `04-library-light.jpg` | Library: liked, downloaded, playlists |
| `05-artist-dark.jpg` | Artist page with the album grid |
| `06-search-empty-dark.jpg` | Empty search state |

## Replacing them

Take new screenshots on a device, then resize so the files stay small — GitHub
shows them fine at 900 px wide:

```bash
cd docs/screenshots
for f in *.jpg; do
  magick "$f[0]" -resize '900x>' -quality 86 -strip "/tmp/$f" && mv "/tmp/$f" "$f"
done
```

Two details that matter here:

- **`[0]`** takes only the first frame. Without it ImageMagick may split a
  multi-frame JPEG into `name-0.jpg`, `name-1.jpg` and leave the resized parts
  next to the original.
- **Write to a temp file, then move it.** Reading and writing the same path in one
  command is what produced those stray numbered files in the first place.

The screenshots are in English because the app was running in English when they
were taken. If you retake them in Russian, the captions under each image in the
README files have to be translated too — they are written out in all eight
languages, not generated from a single source.