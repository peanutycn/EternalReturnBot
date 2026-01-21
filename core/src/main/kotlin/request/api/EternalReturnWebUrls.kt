package cn.luorenmu.request.api

/**
 * Web URLs used for Playwright screenshots.
 */
object EternalReturnWebUrls {
    fun playerPage(nickname: String): String = "https://dak.gg/er/players/${nickname}?gameMode=ALL"

    fun characterPage(
        characterKey: String,
        weaponType: String = "",
        teamMode: String = "SQUAD",
        periodDays: Int = 3,
        tier: String = "diamond_plus",
    ): String =
        "https://dak.gg/er/characters/${characterKey}?teamMode=${teamMode}&weaponType=${weaponType}&period=${periodDays}day&tier=${tier}"

    fun routesPage(routeId: String): String = "https://dak.gg/er/routes/${routeId}"

    fun statisticsPage(
        tier: String = "diamond_plus",
    ): String = "https://dak.gg/er/statistics?tier=${tier}"

    fun officialNews(newsId: String): String = "https://playeternalreturn.com/posts/news/${newsId}"
}
