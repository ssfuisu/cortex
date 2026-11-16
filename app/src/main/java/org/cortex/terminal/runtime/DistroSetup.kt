package org.cortex.terminal.runtime

import org.cortex.terminal.R

/** A distribution the setup offers: what its card shows */
data class DistroOption(
    val id: String,
    val name: String,
    val version: String,
    val maintainer: String,
    val description: Int,
    val icon: Int,
    val color: Int,
    val pm: PackageManager,
    val recommended: Boolean = false
) {
    val isBundled: Boolean get() = id == Distro.BUNDLED_ID
}

/** A desktop the setup offers; [kind] is what cortex-desktop install takes, null for none */
data class DesktopOption(val kind: String?, val name: String, val description: Int, val icon: Int, val recommended: Boolean = false, val heavy: Boolean = false)

/** One step of an installation as the setup shows it, and the command it runs (null: the app's own work) */
data class SetupStep(val title: Int, val command: String? = null)

object DistroSetup {
    val DISTROS = listOf(
        DistroOption(Distro.BUNDLED_ID, "Ubuntu", "24.04 LTS", "Canonical", R.string.distro_ubuntu_2404, R.drawable.ic_distro_ubuntu, 0xFFE95420.toInt(), PackageManager.APT, recommended = true),
        DistroOption("ubuntu-26.04", "Ubuntu", "26.04 LTS", "Canonical", R.string.distro_ubuntu_2604, R.drawable.ic_distro_ubuntu, 0xFFE95420.toInt(), PackageManager.APT),
        DistroOption("debian-13", "Debian", "13", "Debian Project", R.string.distro_debian, R.drawable.ic_distro_debian, 0xFFA81D33.toInt(), PackageManager.APT),
        DistroOption("kali", "Kali Linux", "rolling", "OffSec", R.string.distro_kali, R.drawable.ic_distro_kali, 0xFF367BF0.toInt(), PackageManager.APT),
        DistroOption("arch", "Arch Linux ARM", "rolling", "Arch Linux ARM", R.string.distro_arch, R.drawable.ic_distro_arch, 0xFF1793D1.toInt(), PackageManager.PACMAN),
        DistroOption("fedora-44", "Fedora", "44", "Fedora Project", R.string.distro_fedora, R.drawable.ic_distro_fedora, 0xFF51A2DA.toInt(), PackageManager.DNF),
        DistroOption("opensuse", "openSUSE Tumbleweed", "rolling", "openSUSE Project", R.string.distro_opensuse, R.drawable.ic_distro_opensuse, 0xFF73BA25.toInt(), PackageManager.ZYPPER),
        DistroOption("void", "Void Linux", "rolling", "Void Linux", R.string.distro_void, R.drawable.ic_distro_void, 0xFF478061.toInt(), PackageManager.XBPS)
    )

    val DESKTOPS = listOf(
        DesktopOption("xfce", "XFCE", R.string.desktop_xfce, R.drawable.ic_de_xfce, recommended = true),
        DesktopOption("lxqt", "LXQt", R.string.desktop_lxqt, R.drawable.ic_de_lxqt),
        DesktopOption("mate", "MATE", R.string.desktop_mate, R.drawable.ic_de_mate),
        DesktopOption("kde", "KDE Plasma", R.string.desktop_kde, R.drawable.ic_de_kde, heavy = true),
        DesktopOption("light", "Openbox", R.string.desktop_openbox, R.drawable.ic_de_openbox),
        DesktopOption(null, "", R.string.desktop_none, R.drawable.ic_de_none)
    )

    fun option(id: String): DistroOption? = DISTROS.firstOrNull { it.id == id }

    /** What a distribution needs once, as root, before its package manager works (an image ships no secret keys) */
    fun firstRunCommand(pm: PackageManager): String? = when (pm) {
        PackageManager.PACMAN -> "pacman-key --init && pacman-key --populate"
        else -> null
    }

    /** The package lists, and on Arch everything (a partial upgrade breaks it: -Sy is always -Syu) */
    fun refreshCommand(pm: PackageManager): String = when (pm) {
        PackageManager.APT -> "apt-get update"
        PackageManager.PACMAN -> "pacman -Syu --noconfirm"
        PackageManager.DNF -> "dnf -y makecache"
        PackageManager.ZYPPER -> "zypper -n --gpg-auto-import-keys refresh"
        PackageManager.XBPS -> "xbps-install -Suy xbps"
    }

    /** The steps of an installation of [distro] with [desktop] (kind, or null), in order */
    fun steps(distro: DistroOption, desktop: String?, browser: Boolean): List<SetupStep> {
        val s = mutableListOf(SetupStep(R.string.setup_step_check))
        if (!distro.isBundled) {
            s += SetupStep(R.string.setup_step_find)
            s += SetupStep(R.string.setup_step_download)
        }
        s += SetupStep(R.string.setup_step_unpack)
        s += SetupStep(R.string.setup_step_configure, firstRunCommand(distro.pm))
        if (desktop != null) {
            s += SetupStep(R.string.setup_step_refresh, refreshCommand(distro.pm))
            s += SetupStep(R.string.setup_step_desktop, "cortex-desktop install $desktop")
            if (browser) s += SetupStep(R.string.setup_step_browser, "cortex-desktop browser")
        }
        return s
    }
}
