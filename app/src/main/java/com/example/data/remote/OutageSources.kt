package com.example.data.remote

data class CitySource(
    val name: String,
    val oblenergo: String,
    val jsonUrl: String
)

object OutageSources {
    val CITY_SOURCES: Map<String, String> = mapOf(
        "Київ" to "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/kyiv.json",
        "Дніпро" to "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/dnipro.json",
        "Одеса" to "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/odesa.json",
        "Вінниця" to "https://raw.githubusercontent.com/olnet93/gpv-voe-vinnytsia/main/data/Vinnytsiaoblenerho.json",
        "Черкаси" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Cherkasyoblenergo.json",
        "Чернігів" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Chernihivoblenergo.json",
        "Харків" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Kharkivoblenerho.json",
        "Хмельницький" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Khmelnytskoblenerho.json",
        "Львів" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Lvivoblenerho.json",
        "Полтава" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Poltavaoblenergo.json",
        "Івано-Франківськ" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Prykarpattiaoblenerho.json",
        "Рівне" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Rivneoblenergo.json",
        "Тернопіль" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Ternopiloblenerho.json",
        "Ужгород" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Zakarpattiaoblenerho.json",
        "Запоріжжя" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Zaporizhzhiaoblenergo.json",
        "Житомир" to "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Zhytomyroblenergo.json"
    )

    private val OBLENERGO_NAMES = mapOf(
        "Київ" to "ДТЕК Київські електромережі",
        "Дніпро" to "ДТЕК Дніпровські електромережі",
        "Одеса" to "ДТЕК Одеські електромережі",
        "Вінниця" to "Вінницяобленерго",
        "Черкаси" to "Черкасиобленерго",
        "Чернігів" to "Чернігівобленерго",
        "Харків" to "Харківобленерго",
        "Хмельницький" to "Хмельницькобленерго",
        "Львів" to "Львівобленерго",
        "Полтава" to "Полтаваобленерго",
        "Івано-Франківськ" to "Прикарпаттяобленерго",
        "Рівне" to "Рівнеобленерго",
        "Тернопіль" to "Тернопільобленерго",
        "Ужгород" to "Закарпаттяобленерго",
        "Запоріжжя" to "Запоріжжяобленерго",
        "Житомир" to "Житомиробленерго"
    )

    val CITIES: List<CitySource> = CITY_SOURCES.map { (cityName, url) ->
        CitySource(
            name = cityName,
            oblenergo = OBLENERGO_NAMES[cityName] ?: "${cityName}обленерго",
            jsonUrl = url
        )
    }

    fun getCitySource(name: String): CitySource? {
        return CITIES.find { it.name.equals(name, ignoreCase = true) }
    }
}
