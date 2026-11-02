# Camera Check

A tiny read-only app that reports what a phone lets ordinary camera apps use: every camera/lens
it exposes (including physical lenses behind the logical camera, and readable unlisted IDs),
manual ISO/shutter/focus ranges, RAW (DNG) and maximum-resolution capture, 10-bit/Ultra HDR, and
the maker's own processing modes (Camera Extensions: Auto, Bokeh, Face Retouch, HDR, Night),
including which manual controls each mode accepts. It never takes a photo.

Built by `.github/workflows/camera-check.yml` (branch `camera-check`). Install, open, allow camera
access, then **Share report** or **Save to Downloads**. Run it on each phone (S23 Ultra, A54).
