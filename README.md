# NguonC CloudStream extension

CloudStream provider for [phim.nguonc.com](https://phim.nguonc.com) (Phim Nguồn C).
Uses the site's public JSON API directly — no HTML scraping.

## Features

- Home page rows: Phim mới cập nhật, Phim đang chiếu, Phim lẻ, Phim bộ, TV Shows,
  Hoạt hình, and country rows (Hàn Quốc, Trung Quốc, Âu Mỹ) with pagination.
- Search via `/api/films/search?keyword=`.
- Movie / series detail from `/api/film/{slug}` including genres, year, country,
  cast, duration, and airing status.
- Direct `.m3u8` playback per server, with a fallback to the embed player when a
  direct stream is not exposed.

## API endpoints used

| Purpose | Endpoint |
| --- | --- |
| Newest films | `GET /api/films/phim-moi-cap-nhat?page={n}` |
| Catalog lists | `GET /api/films/danh-sach/{phim-le\|phim-bo\|tv-shows\|phim-dang-chieu}?page={n}` |
| Genre / country | `GET /api/films/the-loai/{slug}` / `GET /api/films/quoc-gia/{slug}` |
| Search | `GET /api/films/search?keyword={q}` |
| Detail + episodes | `GET /api/film/{slug}` |

## Setup (one time)

1. Create a GitHub repository and push this project to its `master` (or `main`) branch.
2. Create an empty orphan branch named `builds`:

   ```bash
   git checkout --orphan builds
   git rm -rf .
   git commit --allow-empty -m "init builds"
   git push origin builds
   git checkout master
   ```

3. Edit `repo.json` and replace `YOUR_USERNAME/YOUR_REPO` with your repository.
4. Push — the GitHub Actions workflow compiles the plugin and publishes
   `NguonC.cs3` + `plugins.json` to the `builds` branch automatically.

## Install in CloudStream

Add this repository URL in CloudStream (Settings → Extensions → Add repository):

```
https://raw.githubusercontent.com/YOUR_USERNAME/YOUR_REPO/master/repo.json
```

Then install **NguonC** from the repository list.

## Build locally

```bash
./gradlew make makePluginsJson
```

The compiled plugin is written to `NguonC/build/NguonC.cs3`.

## Notes

- phim.nguonc.com sits behind Cloudflare and blocks most datacenter/VPN IPs.
  Normal residential/mobile connections (the typical CloudStream use case) work fine.
- Most content is Vietsub with subtitles burned into the video, so no separate
  subtitle files are emitted.
