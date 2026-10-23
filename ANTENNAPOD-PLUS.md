# AntennaPod+

AntennaPod+ is a modified version of [AntennaPod](https://github.com/AntennaPod/AntennaPod), made by rzrogers for personal use. Work started on 2026-10-23.

- **License:** GNU General Public License, version 3 (GPL-3.0), the same as AntennaPod. See [LICENSE](LICENSE).
- **Source code:** https://github.com/rzrogers/AntennaPod (branch `playlists`). Each release links to the exact commit it was built from.
- **Support:** The AntennaPod team does **not** provide support for this unofficial version. Please don't report AntennaPod+ problems to AntennaPod's forum or bug tracker.

## Changes from AntennaPod

- **Own app ID and name:** installs as "AntennaPod+" with app ID `de.danoeh.antennapod.plus`, so it sits beside official AntennaPod.
- **Version suffix:** the version name ends in `-plusN`, where N is the build number.
- **Build workflow:** `.github/workflows/antennapod-plus-build.yml` builds a signed APK on every push to `playlists` and attaches it to a GitHub release.
- **Notice in Settings:** the notice for unofficial versions now names AntennaPod+, its author, its license and its source code. The "Project" section of Settings stays hidden, because its links go to the AntennaPod team.
- **Landing page:** `.github/README.md` is the fork's GitHub landing page; AntennaPod's own `README.md` is unchanged.
- **Playlists (in progress):** saved playlists beside the single queue.
  - Storage: three new database tables (`Playlists`, `PlaylistItems`, `PlaylistFeeds`), created when the database opens if they're missing. AntennaPod's own tables and database version number are unchanged, so AntennaPod can still import an AntennaPod+ database export (it ignores the extra tables). When episodes or shows are deleted, their playlist entries are deleted too.
  - Screens (build 5): a "Playlists" item in the main menu (it can be reordered, hidden or put in the bottom bar like the others) opens a list of playlists with their episode counts; create, rename and delete playlists there. Each playlist screen has drag-to-reorder, swipe-to-remove with undo, and a Sort menu that rearranges the playlist once.
  - "Add to playlist…" (build 5) in the episode long-press menu, the multi-select menu and the episode screen's menu: a pop-up of playlists with checkboxes, plus "New playlist…". One episode can be in several playlists.
  - Finished episodes drop out (build 5): a database trigger removes an episode from every playlist as soon as it's marked played, however that happens. Finished episodes can't be added to a playlist.
