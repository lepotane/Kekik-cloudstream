package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.la"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/"                                       to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/categories/nette-ilk-filmler/"               to "Nette İlk Filmler",
        "${mainUrl}/load/page/sayfano/home-series/"                                to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle2/"           to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/"                                      to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/"                              to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/"                                  to "En Çok Beğenilenler",
        "${mainUrl}/load/page/sayfano/genres/aile-filmleri-izleyin-6/"             to "Aile Filmleri",
        "${mainUrl}/load/page/sayfano/genres/aksiyon-filmleri-izleyin-5/"          to "Aksiyon Filmleri",
        "${mainUrl}/load/page/sayfano/genres/animasyon-filmlerini-izleyin-5/"      to "Animasyon Filmleri",
        "${mainUrl}/load/page/sayfano/genres/belgesel-filmlerini-izle-1/"          to "Belgesel Filmleri",
        "${mainUrl}/load/page/sayfano/genres/bilim-kurgu-filmlerini-izleyin-3/"    to "Bilim Kurgu Filmleri",
        "${mainUrl}/load/page/sayfano/genres/komedi-filmlerini-izleyin-1/"         to "Komedi Filmleri",
        "${mainUrl}/load/page/sayfano/genres/korku-filmlerini-izle-4/"             to "Korku Filmleri",
        "${mainUrl}/load/page/sayfano/genres/romantik-filmleri-izle-2/"            to "Romantik Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val url = request.data.replace("sayfano", page.toString())
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*", "X-Requested-With" to "fetch"
        )
        val doc = app.get(url, headers = headers, referer = mainUrl, interceptor = interceptor)
        if (!doc.toString().contains("Sayfa Bulunamadı")) {
            val aa: HDFC = objectMapper.readValue(doc.toString())
            val document = Jsoup.parse(aa.html)
            val home = document.select("a").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home)
        }
        return newHomePageResponse(request.name, emptyList())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()

        return response.results.mapNotNull { resultHtml ->
            val document = Jsoup.parse(resultHtml)
            val title     = document.selectFirst("h4.title")?.text() ?: return@mapNotNull null
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document
        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val rating      = document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()?.toRatingInt()
        val actors      = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) { this.posterUrl = recPosterUrl }
        }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
                ?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }

            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(epHref) { this.name = epName; this.season = epSeason; this.episode = epEpisode }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.rating          = rating
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")
                ?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.rating          = rating
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // ✅ Güncel loadLinks
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document

        // 1. Film sayfasındaki iframe
        val iframe = document.selectFirst("iframe")?.attr("data-src")
        Log.d("HDCH", "iframe: $iframe")

        if (iframe != null) {
            // 2. embed sayfasını aç
            val embedDoc = app.get(iframe, referer = data, interceptor = interceptor).document
            val realIframe = embedDoc.selectFirst("iframe")?.attr("src")
            Log.d("HDCH", "realIframe: $realIframe")

            if (realIframe != null) {
                // 3. asıl player'i Cloudstream extractorlarına ver
                loadExtractor(realIframe, iframe, subtitleCallback, callback)
            }
        }
        return true
    }

    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String,
                    @JsonProperty("meta") val meta: Meta)
    data class Meta(@JsonProperty("title") val title: String,
                    @JsonProperty("canonical") val canonical: Boolean,
                    @JsonProperty("keywords") val keywords: Boolean)
}
