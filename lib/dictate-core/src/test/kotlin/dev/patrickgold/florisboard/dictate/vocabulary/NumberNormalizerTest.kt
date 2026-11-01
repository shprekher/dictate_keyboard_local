/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.vocabulary

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class NumberNormalizerTest : FunSpec({
    context("supported unambiguous expressions") {
        withData(
            "twenty five" to "25",
            "twenty-five" to "25",
            "twenty" to "20",
            "пятьсот" to "500",
            "one hundred and five" to "105",
            "two thousand three hundred forty five" to "2345",
            "двести тридцать четыре" to "234",
            "две тысячи пятьсот" to "2500",
            "тысяча рублей" to "1000 рублей",
            "один миллион двести тысяч тридцать два" to "1200032",
            "I paid five dollars." to "I paid 5 dollars.",
            "Заплатил пять рублей." to "Заплатил 5 рублей.",
            "minus five degrees" to "-5 degrees",
            "минус пять градусов" to "-5 градусов",
            "three point zero five" to "3.05",
            "ноль запятая ноль пять" to "0,05",
            "две целых пять десятых" to "2,5",
            "ноль целых пять сотых" to "0,05",
            "twenty five percent" to "25%",
            "двадцать пять процентов" to "25%",
            "at nine thirty" to "at 9:30",
            "в девять сорок пять" to "в 9:45",
            "в двадцать один тридцать" to "в 21:30",
            "May fifth" to "May 5",
            "пятое мая" to "5 мая",
            "двадцать третьего мая" to "23 мая",
            "пятое мая две тысячи двадцать шесть" to "5 мая 2026",
            "twenty first place" to "21st place",
            "the first floor" to "the 1st floor",
            "первый этаж" to "1-й этаж",
            "двадцать первый этаж" to "21-й этаж",
            "Use twenty five samples, потом пять процентов." to "Use 25 samples, потом 5%.",
            "не пять рублей, а шесть рублей" to "не 5 рублей, а 6 рублей",
        ) { (source, expected) ->
            NumberNormalizer.normalize(source) shouldBe expected
            NumberNormalizer.normalize(expected) shouldBe expected // idempotent
        }
    }
    context("ambiguous and protected expressions remain verbatim") {
        withData(
            "one", "один", "one two three", "один два три", "twenty twenty six", "one in a million",
            "one hundred hundred", "двадцать тридцать", "twenty пять", "двадцать five", "сто hundred",
            "three point twenty five", "две с половиной", "полтора", "first impression", "первый раз",
            "в два", "at nine", "в девять шестьдесят", "at twenty five thirty", "around one or two",
            "a hundred reasons", "one and two", "a second thought", "один из нас", "twenty four seven",
            "\"twenty five\"", "«двадцать пять»", "`twenty five`", "“twenty five”", "'twenty five'", "https://host/twenty-five", "test@example.org",
            "A25 B52 01/02/2026 10:30 1.05 5%", "#twenty", "#кринж", "Кринжовый вайб, имба, рофлим.",
            "однажды два", "May. five", "at. nine thirty", "version twenty-five-beta", "May one person come in?",
            "February thirty", "тридцать первое апреля", "thirty first February",
        ) { source -> NumberNormalizer.normalize(source) shouldBe source }
    }
})
