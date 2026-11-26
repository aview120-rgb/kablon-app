package com.autoagent.app

import com.autoagent.app.calls.CallBrain
import com.autoagent.app.calls.HoldClassifier
import com.autoagent.app.data.AppDatabase
import com.autoagent.app.data.RuleIntent
import com.autoagent.app.util.L
import com.autoagent.app.voice.Command
import com.autoagent.app.voice.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class NewFeaturesTest {
    private val now: Long = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 26, 15, 0, 0) }.timeInMillis

    @Before fun setUp() = L.apply(L.HE)

    private fun intent(u: String) = CallBrain.reply(u, AppDatabase.DEFAULT_RULES, hebrew = true).intent

    @Test fun callRulesHebrew() {
        assertEquals(RuleIntent.WHO_IS_THIS, intent("הלו מי זה"))
        assertEquals(RuleIntent.WHO_IS_THIS, intent("רגע, זה רובוט?"))
        assertEquals(RuleIntent.WANT_OWNER, intent("אפשר לדבר איתו?"))
        assertEquals(RuleIntent.WANT_OWNER, intent("איפה הוא? למה הוא לא עונה"))
        assertEquals(RuleIntent.DELIVERY, intent("היי אני השליח עם החבילה, אני למטה"))
        assertEquals(RuleIntent.APPOINTMENT, intent("שלום מדברת ממכבי לגבי התור שלו"))
        assertEquals(RuleIntent.WRONG_NUMBER, intent("סליחה טעות במספר"))
        assertEquals(RuleIntent.GOODBYE, intent("אוקיי תודה רבה ביי"))
        assertEquals(RuleIntent.HELLO, intent("שלום"))
        assertEquals(RuleIntent.URGENT, intent("זה דחוף, קרה משהו"))
        assertEquals(RuleIntent.TAKE_MESSAGE, intent("תגיד לו שהתקשרתי"))
        assertEquals(RuleIntent.CALLBACK_TIME, intent("שיחזיר לי צלצול בבקשה"))
        assertEquals(CallBrain.UNKNOWN, intent("בלה בלה בלה"))
        assertTrue(CallBrain.reply("בלה בלה", AppDatabase.DEFAULT_RULES, hebrew = true).text.startsWith("אני נציג דיגיטלי ולא הבנתי"))
        assertTrue(CallBrain.reply("מי זה", AppDatabase.DEFAULT_RULES, hebrew = true).text.startsWith("אני הנציג הדיגיטלי"))
    }

    @Test fun holdClassifier() {
        assertEquals(HoldClassifier.Kind.IVR, HoldClassifier.classify("שיחתך חשובה לנו, אנא המתן על הקו"))
        assertEquals(HoldClassifier.Kind.IVR, HoldClassifier.classify("מקומך בתור הוא שלוש"))
        assertEquals(HoldClassifier.Kind.IVR, HoldClassifier.classify("לתורים הקש אחת"))
        assertEquals(HoldClassifier.Kind.HUMAN, HoldClassifier.classify("שלום, מדברת רונית, במה אפשר לעזור"))
        assertEquals(HoldClassifier.Kind.HUMAN, HoldClassifier.classify("כן שלום"))
        assertEquals(HoldClassifier.Kind.IVR, HoldClassifier.classify("Your call is important to us, please hold"))
        assertEquals(HoldClassifier.Kind.HUMAN, HoldClassifier.classify("Hi, this is Sarah speaking, how can I help"))
    }

    @Test fun voiceHoldAndWhatsapp() {
        assertEquals(Command.CallNumber(listOf("קופת חולים", "לקופת חולים"), watchHold = true),
            VoiceCommands.parse("תתקשר לקופת חולים ותודיע לי כשנציג עונה", now))
        assertEquals(Command.CallNumber(listOf("מכבי", "למכבי"), watchHold = true),
            VoiceCommands.parse("תחייג למכבי ותעדכן אותי כשעונים", now))
        assertEquals(Command.CallNumber(listOf("dana"), watchHold = true),
            VoiceCommands.parse("call Dana and tell me when someone answers", now))
        val w = VoiceCommands.parse("תשלח וואטסאפ לדנה מחר בשמונה שאני מאחר", now)
        assertTrue("$w", w is Command.ScheduleSms && w.whatsapp && w.targets.first() == "דנה" && w.text == "אני מאחר")
        val w2 = VoiceCommands.parse("תשלחי הודעה בווטסאפ לאמא בשש: אני בדרך", now)
        assertTrue("$w2", w2 is Command.ScheduleSms && w2.whatsapp && w2.targets.first() == "אמא")
        assertEquals("+972501234567", VoiceCommands.toInternational("050-1234567"))
        assertEquals(Command.ToggleAutoAnswer(true), VoiceCommands.parse("תשים מענה אוטומטי", now))
        assertEquals(Command.ReadSummaries, VoiceCommands.parse("יש לי הודעות חדשות?", now))
    }
}
