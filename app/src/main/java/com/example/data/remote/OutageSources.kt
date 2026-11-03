package com.example.data.remote

data class CitySource(
    val name: String,
    val oblenergo: String,
    val jsonUrl: String
)

object OutageSources {
    val CITIES = listOf(
        CitySource("Київ", "ДТЕК Київські електромережі", "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/kyiv.json"),
        CitySource("Дніпро", "ДТЕК Дніпровські електромережі", "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/dnipro.json"),
        CitySource("Одеса", "ДТЕК Одеські електромережі", "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/odesa.json"),
        CitySource("Вінниця", "Вінницяобленерго", "https://raw.githubusercontent.com/olnet93/gpv-voe-vinnytsia/main/data/Vinnytsiaoblenerho.json"),
        CitySource("Черкаси", "Черкасиобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Cherkasyoblenergo.json"),
        CitySource("Чернігів", "Чернігівобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Chernihivoblenergo.json"),
        CitySource("Харків", "Харківобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Kharkivoblenerho.json"),
        CitySource("Хмельницький", "Хмельницькобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Khmelnytskoblenerho.json"),
        CitySource("Львів", "Львівобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Lvivoblenerho.json"),
        CitySource("Полтава", "Полтаваобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Poltavaoblenergo.json"),
        CitySource("Івано-Франківськ", "Прикарпаттяобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Prykarpattiaoblenerho.json"),
        CitySource("Рівне", "Рівнеобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Rivneoblenergo.json"),
        CitySource("Тернопіль", "Тернопільобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Ternopiloblenerho.json"),
        CitySource("Ужгород", "Закарпаттяобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Zakarpattiaoblenerho.json"),
        CitySource("Запоріжжя", "Запоріжжяобленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Zaporizhzhiaoblenergo.json"),
        CitySource("Житомир", "Житомиробленерго", "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Zhytomyroblenergo.json")
    )

    fun getCitySource(name: String): CitySource? {
        return CITIES.find { it.name.equals(name, ignoreCase = true) }
    }
}
