package com.pcremote.nav

import java.net.URLEncoder

enum class Mode(val route: String) { SCREEN("screen"), REMOTE("remote"), MEDIA("media"), SLIDES("slides"), PAD("pad"), FILES("files") }

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ARG_ID = "id"

    fun start(pcCount: Int, onboarded: Boolean) = if (pcCount == 0 && !onboarded) ONBOARDING else HOME

    fun modePattern(m: Mode) = "pc/{$ARG_ID}/${m.route}"

    fun mode(id: String, m: Mode) = "pc/${URLEncoder.encode(id, "UTF-8").replace("+", "%20")}/${m.route}"
}
