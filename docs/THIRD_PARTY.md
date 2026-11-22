# Third-party material

Dereel's code is written for Dereel. This file lists what in the repository comes from somewhere
else. Libraries are not copied into the repository: they are declared in
`gradle/libs.versions.toml` and downloaded by Gradle under their own licenses.

## Platform logos

| Files | What | Source | License of the artwork |
|---|---|---|---|
| `app/src/main/res/drawable/ic_logo_instagram.xml`, `ic_logo_youtube.xml`, `ic_logo_facebook.xml`, `ic_logo_snapchat.xml` | The outline of each logo: the path data of the Simple Icons files `instagram.svg`, `youtube.svg`, `facebook.svg` and `snapchat.svg`, unchanged | [Simple Icons](https://simpleicons.org), `develop` branch, fetched 22 Nov 2026 | [CC0 1.0](https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md). None of the four has a separate license entry in Simple Icons' data file. |

Added by Dereel around those outlines: the colours (Instagram's gradient is a five-stop
approximation, not an official file), a white shape under the YouTube triangle and the Facebook
"f" so they stay white on any background, and a black outline on the Snapchat ghost.

**The license covers the drawing, not the brand.** Instagram and Facebook are trademarks of Meta,
YouTube is a trademark of Google, and Snapchat is a trademark of Snap. Each company publishes
brand guidelines:

- Instagram: <https://about.meta.com/brand/resources/instagram>
- Facebook: <https://about.meta.com/brand/resources/facebook/logo>
- YouTube: <https://www.youtube.com/howyoutubeworks/resources/brand-resources/#logos-icons-and-colors>
- Snapchat: <https://www.snapchat.com/brand-guidelines>

Dereel shows the logos only to identify the apps it works with, next to each app's name. It is
not affiliated with or endorsed by these companies, and nobody was asked for permission. Whether
this use is acceptable for a public release is an open decision: D-15 in `docs/DECISIONS.md`.

The same logos appear in the README's screenshots, because the screenshots show the app.

## Not included

No code, script, asset or branding from SocialLite or Scrolless is in this repository (SRS
SEC-08, decision D-03).
