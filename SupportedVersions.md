# Supported Versions

This file tracks compatibility between the Belfius Mobile Android app and the Belfius-Root Xposed module.

## Minimum Belfius app version enforced by Belfius

- **Minimum version required by Belfius (as of verification):** v26.1.0 (260781255)
- **Verified/stated on:** 02/10/2026
- **Note:** Belfius does not allow using versions older than v26.1.0 (as of last check). This means the minimum we must support is v26.1.0 (260781255).

## Compatibility table

| Belfius App Version | Version Code | Belfius-Root Support | Patched in Belfius-Root (first time) | Tested with Belfius-Root versions | Still allowed by Belfius? | Notes |
|--------------------:|------------:|:-------------------:|:-----------------------------------:|:--------------------------------|:------------------------:|:------|
| v26.3.0 | 262601628 | ❌ | — | — | ✅ | Current version as of 17/09/2026 - Unpatched |
| v26.1.0 | 260781255 | ❌ | — | — | ✅ | Belfius v26.1.0 introduced new changes, which appear to include explicit anti-hooking detection, which appear to trigger a memory exhaustion attack when it detects any hooking framework. |
| v25.4.1 | 260201622 | ✅ | v1.1.2 | - v1.1.2 | ❌ | Legacy |
| v25.4.0 | 253451500 | ✅ | v1.1.1 | - v1.1.1 | ❌ | Legacy |
| v25.3.0 | 252812031 | ✅ | v1.1.0 | - v1.1.0, v1.1.1 | ❌ | Legacy |
| v25.2.0 | 251701137 | ✅ | v1.0.6 | - v1.0.6, v1.0.7, v1.1.0 | ❌ | Legacy |
| v24.5.1 | 250791650 | ✅ | v1.0.5 | - v1.0.5, v1.0.6 | ❌ | Legacy |
| v25.1.0 | 250791650 | ✅ | v1.0.6 | - v1.0.6 | ❌ | Legacy |
| v24.4.1 | 250361401 | ✅ | v1.0.4 | - v1.0.4, v1.0.5 | ❌ | Legacy |
| v24.4.0 | 243321017 | ✅ | v1.0.3 | - v1.0.3, v1.0.4 | ❌ | Legacy |
| v24.3.2 | 242891651 | ✅ | v1.0.1 | - v1.0.1, v1.0.2 | ❌ | Legacy |
| v24.3.1 | 242742122 | ✅ | v1.0.1 | - v1.0.1 | ❌ | Legacy |
| v24.1.0 | 240801805 | ✅ | v1.0.0 | - v1.0.0, v1.0.1 | ❌ | Legacy |

## Notes on finding APKs

- The Version Code can be used to download a specific APK via FOSS app stores like [Aurora Store](https://auroraoss.com/) (manual download by version code / specific version).



### Testing note
When verifying a new version with root bypass, also check the in-app "About this app" screen (top-right 3-dot menu → "over deze app") — it must show `Rooted: false` on a rooted device for the bypass to be considered fully effective.
