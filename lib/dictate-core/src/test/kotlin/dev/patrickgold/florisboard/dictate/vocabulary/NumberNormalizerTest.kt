/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.vocabulary

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class NumberNormalizerTest : FunSpec({
    test("spoken decimals tolerate ASR casing, numeric chunks and duplicated punctuation") {
        val fractions = mapOf("четырнадцать" to "14", "14" to "14", "один четыре" to "14",
            "двадцать пять" to "25", "ноль четырнадцать" to "014", "00 четырнадцать" to "0014",
            "ноль 04" to "004", "ноль" to "0", "сто двадцать пять" to "125")
        for (whole in listOf("тринадцать", "13")) for ((marker, mark) in listOf("запятая" to ",", "точка" to "."))
            for ((fraction, digits) in fractions) for (left in listOf(" ", "$mark ")) for (right in listOf(" ", "$mark ")) {
                val expected = "13$mark$digits"
                NumberNormalizer.normalize("$whole$left${marker.uppercase()}$right$fraction") shouldBe expected
                NumberNormalizer.normalize(expected) shouldBe expected
            }
    }
    test("decimal separator survives punctuation introduced by cleanup") {
        TranscriptRefinement.refine("тринадцать запятая четырнадцать", PersonalVocabulary(emptyList()),
            VocabularyCorrector(Lexicon { true }), true) { "Тринадцать, запятая четырнадцать." } shouldBe "13,14."
    }
    test("personal vocabulary protects punctuation words") {
        NumberNormalizer.normalize("это запятая", listOf(4..10)) shouldBe "это запятая"
    }
    context("quantities above 9999 stay in words") {
        withData(
            "он заработал миллион долларов" to "он заработал миллион долларов",
            "два миллиона рублей" to "два миллиона рублей",
            "двадцать пять тысяч рублей" to "двадцать пять тысяч рублей",
            "десять тысяч" to "десять тысяч",
            "тысячи людей" to "тысячи людей",
            "миллионы причин" to "миллионы причин",
            "десять тысяч девятьсот девяносто девять" to "десять тысяч девятьсот девяносто девять",
            "тысяча рублей" to "1000 рублей",
            "две тысячи пятьсот рублей" to "2500 рублей",
            "девять тысяч девятьсот девяносто девять" to "9999",
            "сто тысяч" to "сто тысяч",
            "1,5 миллиона" to "1,5 миллиона",
            "9999" to "9999",
            "10000" to "10000",
        ) { (source, expected) ->
            NumberNormalizer.normalize(source) shouldBe expected
            NumberNormalizer.normalize(expected) shouldBe expected
        }
    }

    context("ASR punctuation inside one number") {
        withData(
            "Проверим 67.0 274, точка 15" to "Проверим 67.0 274.15",
            "274, точка 15" to "274.15",
            "13, запятая 14" to "13,14",
            "13. запятая 14" to "13,14",
            "восемь, дробь шестнадцать" to "8/16",
            "8 дробь 016" to "8/016",
            "67.0 274.15" to "67.0 274.15",
            "13, 14" to "13, 14",
            "2026-11-18" to "2026-11-18",
            "8 916 123 45 67" to "89161234567",
        ) { (source, expected) ->
            NumberNormalizer.normalize(source) shouldBe expected
            NumberNormalizer.normalize(expected) shouldBe expected
        }
    }

    context("spoken decimals") {
        withData(
            "две целых и три десятых" to "2,3",
            "тридцать целых одна сотая" to "30,01",
            "сто восемь целых тридцать шесть тысячных" to "108,036",
            "две целых три десятитысячных" to "2,0003",
            "ноль целых и семьсот двадцать девять тысячных" to "0,729",
            "два и семь сотых" to "2,07",
            "семь десятых" to "0,7",
            "минус пять целых одна десятая" to "-5,1",
            "пять целых и одна десятая миллиона" to "5,1 миллиона",
            "две целых пять" to "две целых пять",
            "three point twenty five" to "three point twenty five",
        ) { (source, expected) ->
            NumberNormalizer.normalize(source) shouldBe expected
            NumberNormalizer.normalize(expected) shouldBe expected
        }
    }

    test("cleanup punctuation no longer splits a decimal") {
        val personal = PersonalVocabulary(emptyList())
        val corrector = VocabularyCorrector(Lexicon { true })
        for (cleanup in listOf({ text: String -> text }, { _: String -> "Проверим 67.0 274, точка 15" })) {
            TranscriptRefinement.refine("Проверим 67.0 274 точка 15", personal, corrector, true, cleanup) shouldBe
                "Проверим 67.0 274.15"
        }
    }

    context("oblique cases and X с половиной") {
        withData(
            "шестнадцать с половиной" to "16,5",
            "две с половиной" to "2,5",
            "один с половиной" to "1,5",
            "шестнадцать с половиной рублей" to "16,5 рублей",
            "около шестнадцати" to "около 16",
            "шестнадцати" to "16",
            "шестнадцатью" to "16",
            "с шестнадцатью" to "с 16",
            "около пяти" to "около 5",
            "около двух" to "около 2",
            "трёх" to "3",
            "трех" to "3",
            "двумя" to "2",
            "двадцати пяти" to "25",
            "к двадцати" to "к 20",
        ) { (source, expected) ->
            NumberNormalizer.normalize(source) shouldBe expected
            NumberNormalizer.normalize(expected) shouldBe expected
        }
    }

    context("supported unambiguous expressions") {
        withData(
            "twenty five" to "25",
            "через пять минут" to "через 5 минут",
            "восемь дрог шестнадцать" to "8 дрог 16",
            "восемь, дробь шестнадцать" to "8/16",
            "8 дробь 016" to "8/016",
            "одна заявка и ноль ответов" to "1 заявка и 0 ответов",
            "one" to "1",
            "один" to "1",
            "в два" to "в 2",
            "at nine" to "at 9",
            "around one or two" to "around 1 or 2",
            "one and two" to "1 and 2",
            "one, two, three" to "1, 2, 3",
            "однажды два" to "однажды 2",
            "May. five" to "May. 5",
            "May one person come in?" to "May 1 person come in?",
            "Улица Рябиновая, дом тридцать семь, строение один." to "Улица Рябиновая, дом 37, строение 1.",
            "one two three" to "123",
            "один два три" to "123",
            "ноль пять два" to "052",
            "0 пять 2" to "052",
            "zero 07 two" to "0072",
            "плюс семь девятьсот шестнадцать 123 сорок пять шестьдесят семь" to "+79161234567",
            "восемь девятьсот шестнадцать 123 сорок пять шестьдесят семь" to "89161234567",
            "+7 (916) 123-45-67" to "+79161234567",
            "8 (916) 007-00-01" to "89160070001",
            "плюс 7 (916) 123-45-67" to "+79161234567",
            "плюс семь, девятьсот шестнадцать, 123, сорок пять, 67." to "+79161234567.",
            "8 916, 007, 00, 01" to "89160070001",
            "Call (212) 555-0188." to "Call 2125550188.",
            "one hundred five" to "105",
            "один запятая два запятая три" to "1, 2, 3",
            "twenty-five" to "25",
            "twenty" to "20",
            "пятьсот" to "500",
            "one hundred and five" to "105",
            "two thousand three hundred forty five" to "2345",
            "двести тридцать четыре" to "234",
            "две тысячи пятьсот" to "2500",
            "тысяча рублей" to "1000 рублей",
            "один миллион двести тысяч тридцать два" to "один миллион двести тысяч тридцать два",
            "I paid five dollars." to "I paid 5 dollars.",
            "Заплатил пять рублей." to "Заплатил 5 рублей.",
            "minus five degrees" to "-5 degrees",
            "минус пять градусов" to "-5 градусов",
            "three point zero five" to "3.05",
            "Готово, запятая можно идти." to "Готово, можно идти.",
            "Я думаю запятая что да." to "Я думаю, что да.",
            "Осталось три запятая остальное потом." to "Осталось 3, остальное потом.",
            "тринадцать точка два" to "13.2",
            "тринадцать запятая два" to "13,2",
            "13 точка два" to "13.2",
            "тринадцать запятая 02" to "13,02",
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
            "дом один на всю деревню", "2026-11-18", "twenty twenty six", "one in a million",
            "one hundred hundred", "двадцать тридцать", "twenty пять", "двадцать five", "сто hundred",
            "three point twenty five", "полтора", "first impression", "первый раз",
            "в девять шестьдесят", "at twenty five thirty",
            "a hundred reasons", "a second thought", "один из нас", "twenty four seven",
            "\"twenty five\"", "«двадцать пять»", "`twenty five`", "“twenty five”", "'twenty five'", "https://host/twenty-five", "test@example.org",
            "A25 B52 01/02/2026 10:30 1.05 5%", "#twenty", "#кринж", "Кринжовый вайб, имба, рофлим.",
            "at. nine thirty", "version twenty-five-beta",
            "February thirty", "тридцать первое апреля", "thirty first February",
            "Точка зрения", "точка на карте", "слово запятая", "здесь нужна запятая",
            "Запятая разделяет части предложения.", "Он сказал «запятая».", "13, 14", "13.14",
            "пять шесть рублей", "«+7 (916) 123-45-67»",
        ) { source -> NumberNormalizer.normalize(source) shouldBe source }
    }
})
