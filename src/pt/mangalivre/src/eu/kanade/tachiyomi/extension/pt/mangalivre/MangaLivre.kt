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
        SimpleDateFormat("MMMM dd, yyyy", Locale("pt")),
    ) {
    override val client = super.client.newBuilder()
        .rateLimit(2)
        .build()
}