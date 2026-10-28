# Quick panel — design mockups

Rendered at 1920×1080 over a real launcher screenshot (`backdrop.png`), so the
glass is judged against the actual wallpaper instead of a flat gradient.

| File | Variant |
|---|---|
| `panel-a.png` | A — frosted right sheet, same glass tokens as Home dock |
| `panel-b.png` | B — dark companion sheet, glass type and radii, near-black slab |
| `panel-c.png` | C — bottom glass dock, mirrors Home's dock position |
| `panel-d.png` | D — centred glass card, reads over any wallpaper |

Tokens come from `tv-glass-launcher/assets/themes/glass.json`: 20dp tile
corners, 29dp sheet corners, `#24FFFFFF` → `#0DFFFFFF` dock fill, `#38FFFFFF`
hairline, 2dp `#F2FFFFFF` focus ring, 1.04 focus lift, light display type with
−0.03em tracking, uppercase 0.10em captions.

Everything is paintable with `ShapeDrawable` — no live blur, no elevation
tricks. The sheet is a translucent fill over the panel's own window dim.

Regenerate:

```powershell
npx playwright screenshot --viewport-size "1920,1080" --wait-for-timeout 700 panel-a.html panel-a.png
```

Each variant also shows a Home button next to the gear: that is the shortcut
back to `com.ajink.tvglass`, so the panel and launcher can open each other.
