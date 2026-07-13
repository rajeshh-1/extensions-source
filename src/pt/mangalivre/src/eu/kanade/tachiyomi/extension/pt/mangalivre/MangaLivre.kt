package eu.kanade.tachiyomi.extension.pt.mangalivre

import eu.kanade.tachiyomi.multisrc.madara.Madara
import keiyoushi.network.rateLimit
import java.text.SimpleDateFormat
import java.util.Locale

class MangaLivre :
    Madara(
        "Manga Livre",
        "https://mangalivre.to",
        "pt-BR",
        SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("pt")),
    ) {
    override val client = super.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun chapterListSelector() = ".listing-chapters-wrap .chapter-box"

    override fun chapterDateSelector() = ".chapter-date"
}