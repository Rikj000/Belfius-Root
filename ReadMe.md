# Belfius Root

<p align="left">
    <a href="https://xdaforums.com/t/release-v1-0-0-belfius-root.4680025/">
        <img src="https://img.shields.io/badge/XDA_Thread-Belfius_Root-orange" alt="XDA Developers - Belfius Root">
    </a> <a href="https://github.com/Rikj000/Belfius-Root/blob/development/LICENSE">
        <img src="https://img.shields.io/github/license/Rikj000/Belfius-Root?label=License&logo=gnu" alt="GNU General Public License">
    </a> <a href="https://github.com/Rikj000/Belfius-Root/releases">
        <img src="https://img.shields.io/github/downloads/Rikj000/Belfius-Root/total?label=Total%20Downloads&logo=github" alt="Total Releases Downloaded from GitHub">
    </a> <a href="https://github.com/Rikj000/Belfius-Root/releases/latest">
        <img src="https://img.shields.io/github/v/release/Rikj000/Belfius-Root?include_prereleases&label=Latest%20Release&logo=github" alt="Latest Official Release on GitHub">
    </a> <a href="https://www.iconomi.com/register?ref=zQQPK">
        <img src="https://img.shields.io/badge/ICONOMI-Join-blue?logo=bitcoin&logoColor=white" alt="ICONOMI - The world’s largest crypto strategy provider">
    </a> <a href="https://www.buymeacoffee.com/Rikj000">
        <img src="https://img.shields.io/badge/-Buy%20me%20a%20Coffee!-FFDD00?logo=buy-me-a-coffee&logoColor=black" alt="Buy me a Coffee as a way to sponsor this project!"> 
    </a>
</p>

Simple Xposed module to support the [Belfius](https://play.google.com/store/apps/details?id=be.belfius.directmobile.android) app on Rooted devices!

## Installation
1. Install [Magisk](https://topjohnwu.github.io/Magisk/install.html)
2. Magisk => Settings => Magisk => Check `Zygisk`
3. Magisk => Settings => Magisk => Configure DenyList => UnCheck `Belfius Mobile` (= Default)
4. Install [LSPosed (Zygisk)](https://github.com/LSPosed/LSPosed/releases/latest) + [Shamiko](https://github.com/LSPosed/LSPosed.github.io/releases/latest)
5. Install the [Belfius-Root](https://github.com/Rikj000/Belfius-Root/releases/latest) app
6. LSPosed => Modules => `Belfius Root` => Check `Enable module` + Check `Belfius Mobile`
7. Reboot

## Motivation

Belfius bank was pretty cool about having a rooted device in the past,   
they just threw a dismissible warning about security risks of using a rooted device,   
and then allowed you to keep using their app.

However on 2024-07-01 this changed.   
Nowadays they assume that you're an idiot that will not be able to keep your own device safe if you have root.

I do not agree with that assumption, and likely neither do you.   
Only power users that have a good idea of what they're doing tend to root their own devices.

After only a hand full of transactions, done through the tedious browser process,   
I grew agitated enough to do the research to write this module.

Hope you'll enjoy this module and the ability to stay rooted!

## LSPosed Archived
LSPosed indeed sadly has been archived.   
However XPosed has continued on living in the past through new forks,   
and I believe it will continue to do so.

I've opened an issue on another of my Android projects,   
which helps keep track of the status of upcoming successor forks:   
https://github.com/Rikj000/Android-Auto-XLauncher-Unlocked/issues/6

Android 15 users require these since official LSPosed only supports up to Android 14.   
`@arnii5` reported good results [on XDA](https://xdaforums.com/t/release-v1-0-1-belfius-root.4680025/#post-89782267) with [JingMatrix/LSPosed](https://github.com/JingMatrix/LSPosed) on Android 15.
