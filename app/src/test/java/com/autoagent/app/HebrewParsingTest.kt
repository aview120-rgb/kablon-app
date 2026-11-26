package com.autoagent.app

import com.autoagent.app.calls.CallBrain
import com.autoagent.app.data.AppDatabase
import com.autoagent.app.data.RuleIntent
import com.autoagent.app.inbox.Summarizer
import com.autoagent.app.receipts.ReceiptParser
import com.autoagent.app.util.L
import com.autoagent.app.util.TimeText
import com.autoagent.app.voice.Command
import com.autoagent.app.voice.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class HebrewParsingTest {
    /** Thursday 2026-11-26 15:00 local time. */
    private val now: Long = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 26, 15, 0, 0) }.timeInMillis

    private fun at(dayOffset: Int, h: Int, m: Int): Long = Calendar.getInstance().apply {
        clear(); set(2026, Calendar.NOVEMBER, 26, h, m, 0); add(Calendar.DAY_OF_YEAR, dayOffset)
    }.timeInMillis

    private fun hm(millis: Long): String = Calendar.getInstance().apply { timeInMillis = millis }.let {
        "%d %02d:%02d".format(it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE))
    }

    @Before fun setUp() = L.apply(L.HE)

    private fun alarm(text: String): Command.SetAlarm {
        val c = VoiceCommands.parse(text, now)
        assertTrue("Expected alarm for \"$text\" but got $c", c is Command.SetAlarm)
        return c as Command.SetAlarm
    }

    private fun assertAlarm(text: String, expected: Long) = assertEquals(text, hm(expected), hm(alarm(text).at))

    @Test fun hebrewAlarms() {
        assertAlarm("תעיר אותי בשבע", at(1, 7, 0))
        assertAlarm("שעון מעורר בשבע בערב", at(0, 19, 0))
        assertAlarm("תזכיר לי בשבע לקנות חלב", at(0, 19, 0))
        assertAlarm("תעיר אותי בשבע בבוקר", at(1, 7, 0))
        assertAlarm("תעיר אותי מחר בשבע וחצי", at(1, 7, 30))
        assertAlarm("תכוון שעון מעורר ל-6:45", at(1, 6, 45))
        assertAlarm("שעון מעורר לשמונה ורבע בערב", at(0, 20, 15))
        assertAlarm("תעיר אותי ברבע לשמונה", at(1, 7, 45))
        assertAlarm("השכמה בעשרים לתשע בבוקר", at(1, 8, 40))
        assertAlarm("תעיר אותי בעוד עשר דקות", now + 10 * 60_000)
        assertAlarm("תעיר אותי בעוד רבע שעה", now + 15 * 60_000)
        assertAlarm("תעיר אותי בעוד שעה וחצי", now + 90 * 60_000)
        assertAlarm("תעיר אותי בעוד שעתיים", now + 120 * 60_000)
        assertAlarm("תעיר אותי בעוד 25 דקות", now + 25 * 60_000)
        assertAlarm("תעיר אותי בחצות", at(1, 0, 0))
        assertAlarm("תעיר אותי בעשר בלילה", at(0, 22, 0))
        assertAlarm("תעיר אותי בשתיים בלילה", at(1, 2, 0))
        assertAlarm("תעיר אותי בשלוש בצהריים", at(1, 15, 0))
        assertAlarm("היי תעיר אותי מחר ב 8 בבוקר בבקשה", at(1, 8, 0))
        assertAlarm("תעיר אותי בשתים עשרה ועשרים", at(1, 0, 20))
        assertAlarm("תעיר אותי באחת עשרה וחצי", at(1, 11, 30))
        assertAlarm("תזכיר לי באחת עשרה וחצי", at(0, 23, 30))
        assertAlarm("תעיר אותי בעשרים וחמש לשבע", at(1, 6, 35))
        assertAlarm("תעיר אותי מחר בשש", at(1, 6, 0))
    }

    @Test fun hebrewReminderLabel() {
        val a = alarm("תזכיר לי בעוד חצי שעה להוציא את הכביסה")
        assertEquals(hm(now + 30 * 60_000), hm(a.at))
        assertTrue(a.label, a.label.contains("להוציא את הכביסה"))
        val b = alarm("תזכיר לי מחר בתשע להתקשר לרופא")
        assertEquals(hm(at(1, 9, 0)), hm(b.at))
        assertTrue(b.label, b.label.contains("להתקשר לרופא"))
    }

    @Test fun hebrewCalls() {
        assertEquals(Command.CallNumber(listOf("דנה", "לדנה")), VoiceCommands.parse("תתקשר לדנה", now))
        assertEquals(Command.CallNumber(listOf("אמא", "לאמא")), VoiceCommands.parse("תתקשר לאמא בבקשה", now))
        assertEquals(Command.CallNumber(listOf("0501234567")), VoiceCommands.parse("תחייג ל-050-1234567", now))
        assertEquals(Command.CallNumber(listOf("0501234567")), VoiceCommands.parse("חייג 050 123 4567", now))
        assertEquals(Command.CallNumber(listOf("יוסי כהן", "ליוסי כהן")), VoiceCommands.parse("תתקשרי ליוסי כהן עכשיו", now))
    }

    private fun sms(text: String): Command.ScheduleSms {
        val c = VoiceCommands.parse(text, now)
        assertTrue("Expected SMS for \"$text\" but got $c", c is Command.ScheduleSms)
        return c as Command.ScheduleSms
    }

    @Test fun hebrewSms() {
        sms("תשלח הודעה לדנה מחר בשמונה שאני מאחר").let {
            assertEquals("דנה", it.targets.first()); assertEquals(hm(at(1, 8, 0)), hm(it.at)); assertEquals("אני מאחר", it.text)
        }
        sms("תשלח לאמא בשש: אני בדרך הביתה!").let {
            assertEquals("אמא", it.targets.first()); assertEquals(hm(at(0, 18, 0)), hm(it.at)); assertEquals("אני בדרך הביתה!", it.text)
        }
        sms("תתזמן הודעה ליוסי בעוד 10 דקות שאומרת תביא חלב").let {
            assertEquals("יוסי", it.targets.first()); assertEquals(hm(now + 10 * 60_000), hm(it.at)); assertEquals("תביא חלב", it.text)
        }
        sms("תשלח sms לדנה מחר בתשע בבוקר תגיד לה שהפגישה בוטלה").let {
            assertEquals("דנה", it.targets.first()); assertEquals(hm(at(1, 9, 0)), hm(it.at)); assertEquals("הפגישה בוטלה", it.text)
        }
        sms("תשלח הודעה ל 0501234567 בשבע וחצי שאני מגיע").let {
            assertEquals("0501234567", it.targets.first()); assertEquals(hm(at(0, 19, 30)), hm(it.at)); assertEquals("אני מגיע", it.text)
        }
        sms("תכתוב לדנה בחמש שאלה: מתי נפגשים?").let {
            assertEquals("דנה", it.targets.first()); assertEquals(hm(at(0, 17, 0)), hm(it.at)); assertEquals("שאלה: מתי נפגשים?", it.text)
        }
    }

    @Test fun hebrewToggleAndSummary() {
        assertEquals(Command.ToggleAutoAnswer(true), VoiceCommands.parse("תפעיל מענה אוטומטי", now))
        assertEquals(Command.ToggleAutoAnswer(false), VoiceCommands.parse("תכבה את המענה האוטומטי", now))
        assertEquals(Command.ToggleAutoAnswer(false), VoiceCommands.parse("בטל מענה אוטומטי", now))
        assertEquals(Command.ToggleAutoAnswer(true), VoiceCommands.parse("מענה אוטומטי פועל", now))
        assertEquals(Command.ReadSummaries, VoiceCommands.parse("מה פספסתי?", now))
        assertEquals(Command.ReadSummaries, VoiceCommands.parse("תקריא לי סיכום הודעות", now))
        assertEquals(Command.Help, VoiceCommands.parse("עזרה", now))
    }

    @Test fun englishStillWorks() {
        L.apply(L.EN)
        assertEquals(hm(at(0, 19, 30)), hm(alarm("set alarm for 7:30 pm").at))
        assertEquals(hm(now + 20 * 60_000), hm(alarm("wake me up in 20 minutes").at))
        assertEquals(Command.CallNumber(listOf("dana")), VoiceCommands.parse("call Dana", now))
        val s = sms("schedule SMS to Dana at 6 pm saying I'm on my way")
        assertEquals("dana", s.targets.first()); assertEquals(hm(at(0, 18, 0)), hm(s.at)); assertEquals("I'm on my way", s.text)
        assertEquals(Command.ToggleAutoAnswer(true), VoiceCommands.parse("turn on auto answer", now))
        assertEquals(Command.ReadSummaries, VoiceCommands.parse("read my summaries", now))
    }

    @Test fun callBrainHebrew() {
        val rules = AppDatabase.DEFAULT_RULES
        assertEquals(RuleIntent.WHO_IS_THIS, CallBrain.reply("שלום, מי זה?", rules, hebrew = true).intent)
        assertEquals(RuleIntent.WHO_IS_THIS, CallBrain.reply("עם מי אני מדבר", rules, hebrew = true).intent)
        assertEquals(RuleIntent.CALLBACK_TIME, CallBrain.reply("מתי הוא יחזור אליי?", rules, hebrew = true).intent)
        assertEquals(RuleIntent.TAKE_MESSAGE, CallBrain.reply("אני רוצה להשאיר הודעה", rules, hebrew = true).intent)
        assertEquals(RuleIntent.URGENT, CallBrain.reply("זה דחוף מאוד", rules, hebrew = true).intent)
        assertEquals(RuleIntent.SALES, CallBrain.reply("יש לנו הצעה מיוחדת לביטוח", rules, hebrew = true).intent)
        assertEquals(CallBrain.UNKNOWN, CallBrain.reply("רציתי לשאול על החבילה מאמזון", rules, hebrew = true).intent)
        assertEquals("אני אעביר את זה לבעל המספר.", CallBrain.reply("בלה בלה", rules, hebrew = true).text)
        assertTrue(CallBrain.reply("מי זה", rules, hebrew = true).text.contains("עוזר"))
        assertEquals("השיחה נענית על ידי עוזר ומוקלטת.", CallBrain.GREETING)
    }

    @Test fun summarizerHebrew() {
        assertTrue(Summarizer.isAsk("תוכל לשלוח לי את הקובץ"))
        assertTrue(Summarizer.isAsk("ותתקשר אליי כשאתה מתפנה"))
        assertTrue(!Summarizer.isAsk("הגעתי הביתה"))
        assertEquals(listOf("250 ש\"ח"), Summarizer.moneyIn("צריך להעביר 250 ש\"ח עד מחר"))
        assertEquals(listOf("₪120"), Summarizer.moneyIn("החשבון יצא ₪120"))
        assertEquals(listOf("80 שקל"), Summarizer.moneyIn("תחזיר לי 80 שקל"))
    }

    @Test fun receiptsHebrew() {
        val r = ReceiptParser.parse("קבלה מסופר פארם 45.90 שקל אתמול")
        assertEquals("סופר פארם", r.merchant); assertEquals(45.90, r.total!!, 0.001); assertEquals("ILS", r.currency)
        val r2 = ReceiptParser.parse("שילמתי בשופרסל 120 שקלים")
        assertEquals("שופרסל", r2.merchant); assertEquals(120.0, r2.total!!, 0.001)
        val r3 = ReceiptParser.parse("RAMI LEVY\n12/11/2026\nסה\"כ לתשלום 89.90")
        assertEquals(89.90, r3.total!!, 0.001); assertTrue(r3.dateMillis != null)
    }

    @Test fun tokenizer() {
        assertEquals(listOf("ב", "שבע", "ו", "חצי"), TimeText.tokenize("בשבע וחצי"))
        assertEquals(listOf("ל", "6:45"), TimeText.tokenize("ל-6:45"))
    }
}
