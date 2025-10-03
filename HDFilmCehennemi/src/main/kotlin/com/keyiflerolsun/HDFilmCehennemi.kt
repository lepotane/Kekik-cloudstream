package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.la"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/" to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/categories/nette-ilk-filmler/" to "Nette İlk Filmler",
        "${mainUrl}/load/page/sayfano/home-series/" to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle2/" to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/" to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/" to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/" to "En Çok Beğenilenler",
        "${mainUrl}/load/page/sayfano/genres/aile-filmleri-izleyin-6/" to "Aile Filmleri",
        "${mainUrl}/load/page/sayfano/genres/aksiyon-filmleri-izleyin-5/" to "Aksiyon Filmleri",
        "${mainUrl}/load/page/sayfano/genres/animasyon-filmlerini-izleyin-5/" to "Animasyon Filmleri",
        "${mainUrl}/load/page/sayfano/genres/belgesel-filmlerini-izle-1/" to "Belgesel Filmleri",
        "${mainUrl}/load/page/sayfano/genres/bilim-kurgu-filmlerini-izleyin-3/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/load/page/sayfano/genres/komedi-filmlerini-izleyin-1/" to "Komedi Filmleri",
        "${mainUrl}/load/page/sayfano/genres/korku-filmlerini-izle-4/" to "Korku Filmleri",
        "${mainUrl}/load/page/sayfano/genres/romantik-filmleri-izle-2/" to "Romantik Filmleri"
    )

    // --- getMainPage(), search(), load() kısmı senin verdiğin gibi, ben değiştirmedim ---

    private fun dcHello(base64Input: String): String {
        val decodedOnce = base64Decode(base64Input)
        val reversedString = decodedOnce.reversed()
        val decodedTwice = base64Decode(reversedString)

        val hdchLink = when {
            decodedTwice.contains("+") -> decodedTwice.substringAfterLast("+")
            decodedTwice.contains(" ") -> decodedTwice.substringAfterLast(" ")
            decodedTwice.contains("|") -> decodedTwice.substringAfterLast("|")
            else -> decodedTwice
        }
        Log.d("HDCH", "decodedTwice $decodedTwice")
        return hdchLink
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, referer = "$mainUrl/", interceptor = interceptor).document
            val base64Input = doc.select("script")
                .mapNotNull { Regex("""Base64\.decode\("([^"]+)""").find(it.data())?.groupValues?.get(1) }
                .firstOrNull()

            if (base64Input != null) {
                val decoded = dcHello(base64Input)
                val lastUrl = if (decoded.startsWith("http")) decoded else "https://$decoded"

                Log.d("HDCH", "[$source] decoded = $lastUrl")

                callback.invoke(
                    ExtractorLink(
                        source = source,
                        name = source,
                        url = lastUrl,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = lastUrl.contains(".m3u8")
                    )
                )
            } else {
                Log.e("HDCH", "[$source] Base64 video bulunamadı!")
            }
        } catch (e: Exception) {
            Log.e("HDCH", "invokeLocalSource hata: ${e.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDCH", "data » $data")
        val document = app.get(data, interceptor = interceptor).document

        document.select("div.alternative-links button.alternative-link").forEach { btn ->
            val source = btn.text().replace("(HDrip Xbet)", "").trim()
            val videoID = btn.attr("data-video")

            val apiGet = app.get(
                "$mainUrl/video/$videoID/",
                interceptor = interceptor,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Requested-With" to "fetch"
                ),
                referer = data
            ).text

            var iframe = Regex("""data-src=\\"([^"]+)""")
                .find(apiGet)?.groupValues?.get(1)
                ?.replace("\\", "")

            Log.d("HDCH", "[$source] iframe = $iframe")

            if (iframe.isNullOrBlank()) return@forEach

            // Rapidrame fix
            if (iframe.contains("rapidrame")) {
                iframe = "$mainUrl/rplayer/" + iframe.substringAfter("?rapidrame_id=")
                Log.d("HDCH", "Rapidrame iframe: $iframe")
            }

            // Eğer bilinen host ise → Cloudstream extractor
            if (
                iframe.contains("close") ||
                iframe.contains("rapidrame") ||
                iframe.contains("filemoon") ||
                iframe.contains("vidmoly") ||
                iframe.contains("streamtape") ||
                iframe.contains("dood")
            ) {
                loadExtractor(iframe, data, subtitleCallback, callback)
            } else {
                // Özel player decode
                invokeLocalSource(source, iframe, subtitleCallback, callback)
            }
        }
        return true
    }

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String, @JsonProperty("meta") val meta: Meta)
    data class Meta(
        @JsonProperty("title") val title: String,
        @JsonProperty("canonical") val canonical: Boolean,
        @JsonProperty("keywords") val keywords: Boolean
    )
}
