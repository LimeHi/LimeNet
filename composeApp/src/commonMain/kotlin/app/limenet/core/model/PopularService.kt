package app.limenet.core.model

/** Небольшие HTTPS-пробы доступности популярных сервисов через выбранный узел. */
data class PopularService(val id: String, val title: String, val url: String)

val POPULAR_SERVICES = listOf(
    PopularService("youtube", "YouTube", "https://www.youtube.com/generate_204"),
    PopularService("instagram", "Instagram", "https://www.instagram.com/"),
    PopularService("telegram", "Telegram", "https://telegram.org/"),
    PopularService("tiktok", "TikTok", "https://www.tiktok.com/"),
    PopularService("discord", "Discord", "https://discord.com/"),
)
