package com.autoagent.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlarmEntity::class,
        ScheduledSend::class,
        KeywordRule::class,
        CallRecord::class,
        InboxEntry::class,
        Receipt::class,
        ActionLog::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarms(): AlarmDao
    abstract fun sends(): SendDao
    abstract fun rules(): RuleDao
    abstract fun calls(): CallRecordDao
    abstract fun inbox(): InboxDao
    abstract fun receipts(): ReceiptDao
    abstract fun logs(): LogDao

    companion object {
        /** v2: Hebrew replies + Hebrew keywords for the built-in call rules. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE keyword_rules ADD COLUMN responseHe TEXT NOT NULL DEFAULT ''")
                for (r in DEFAULT_RULES) {
                    db.execSQL(
                        "UPDATE keyword_rules SET keywords = ?, responseHe = ? WHERE intent = ?",
                        arrayOf<Any>(r.keywords, r.responseHe, r.intent),
                    )
                }
            }
        }

        /** v3: many more Hebrew phrasings and new built-in intents (delivery, appointment, goodbye...). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = upsertDefaults(db)
        }

        fun upsertDefaults(db: SupportSQLiteDatabase) {
            for (r in DEFAULT_RULES) {
                db.query("SELECT COUNT(*) FROM keyword_rules WHERE intent = ?", arrayOf<Any>(r.intent)).use { c ->
                    c.moveToFirst()
                    if (c.getInt(0) > 0) {
                        db.execSQL(
                            "UPDATE keyword_rules SET keywords = ?, response = ?, responseHe = ?, priority = ? WHERE intent = ?",
                            arrayOf<Any>(r.keywords, r.response, r.responseHe, r.priority, r.intent),
                        )
                    } else {
                        db.execSQL(
                            "INSERT INTO keyword_rules (intent, keywords, response, priority, enabled, responseHe) VALUES (?, ?, ?, ?, 1, ?)",
                            arrayOf<Any>(r.intent, r.keywords, r.response, r.priority, r.responseHe),
                        )
                    }
                }
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "autoagent.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        val DEFAULT_RULES = listOf(
            KeywordRule(
                intent = RuleIntent.URGENT,
                keywords = "urgent,emergency,asap,right now,hospital,accident,ambulance," +
                    "דחוף,דחופה,דחופים,חירום,מקרה חירום,בהול,בהולה,בית חולים,בית החולים,תאונה,חשוב מאוד,חשוב ביותר,פצוע,פצועה," +
                    "אמבולנס,מד\"א,משטרה,קרה משהו,משהו קרה,מצב חירום,לא מרגיש טוב,לא מרגישה טוב,נפל,נפלה,מהר,דחיפות",
                response = "I understand it is urgent. I am notifying the owner right now. Please say briefly what happened.",
                responseHe = "הבנתי שזה דחוף. אני מעדכן אותו מיד. אפשר לומר בקצרה מה קרה.",
                priority = 60,
            ),
            KeywordRule(
                intent = RuleIntent.WRONG_NUMBER,
                keywords = "wrong number,sorry wrong," +
                    "טעות,טעיתי,טעות במספר,מספר לא נכון,מספר שגוי,התקשרתי בטעות,חייגתי בטעות,לא התכוונתי,זה לא המספר",
                response = "No problem. Have a good day.",
                responseHe = "אין בעיה. יום טוב.",
                priority = 55,
            ),
            KeywordRule(
                intent = RuleIntent.WHO_IS_THIS,
                keywords = "who is this,who am i speaking,who are you,whose phone,is this a robot,are you a bot,are you human,is this a recording," +
                    "מי זה,מי זאת,מי מדבר,מי מדברת,מי עונה,מי זה מדבר,מי אתה,מי את,עם מי אני מדבר,עם מי אני מדברת,עם מי מדבר," +
                    "של מי הטלפון,של מי המספר,זה המספר של,זה הטלפון של,רובוט,בוט,מענה קולי,מכונה,מחשב,הקלטה,בן אדם,בנאדם,אתה אמיתי,את אמיתית,מה זה",
                response = "I am the digital assistant. The owner cannot answer right now. How can I help?",
                responseHe = "אני הנציג הדיגיטלי. הוא לא יכול לענות כרגע. במה אפשר לעזור?",
                priority = 50,
            ),
            KeywordRule(
                intent = RuleIntent.WANT_OWNER,
                keywords = "can i speak,can i talk,is he there,is she there,put me through,connect me,where is he,where is she," +
                    "לדבר איתו,לדבר איתה,לדבר עם,אפשר לדבר,תעביר אותי,תחבר אותי,תעבירי אותי,הוא שם,היא שם,הוא זמין,היא זמינה,איפה הוא,איפה היא," +
                    "הוא ער,הוא בבית,הוא בעבודה,למה הוא לא עונה,למה לא עונים,תקרא לו,תקראי לו,תן לי אותו,תני לי אותו,אני צריך אותו,אני צריכה אותו",
                response = "The owner is not available right now. You can leave a message and the owner will call you back soon.",
                responseHe = "הוא לא פנוי כרגע. אפשר להשאיר הודעה, והוא יחזור אליך בהקדם.",
                priority = 45,
            ),
            KeywordRule(
                intent = RuleIntent.DELIVERY,
                keywords = "delivery,courier,package,parcel,i am downstairs,at the door,at the gate," +
                    "שליח,שליחה,משלוח,חבילה,חבילות,דואר,דואר שליחים,אני למטה,אני בכניסה,אני ליד הבית,אני בחוץ,אני ליד הדלת,קוד לבניין,קוד לשער," +
                    "איפה להשאיר,להשאיר ליד הדלת,וולט,וולט,תן ביס,הזמנה,אוכל,פיצה,אמזון,עלי אקספרס,איביי",
                response = "Thanks. I am letting the owner know about the delivery now. If you have instructions, please say them.",
                responseHe = "תודה. אני מעדכן אותו עכשיו על המשלוח. אם יש הוראות, אפשר לומר אותן ואני אעביר.",
                priority = 42,
            ),
            KeywordRule(
                intent = RuleIntent.APPOINTMENT,
                keywords = "appointment,clinic,doctor,test results,confirm," +
                    "תור,תורים,קופת חולים,קופה,מרפאה,בדיקה,בדיקות,רופא,רופאה,מכבי,כללית,מאוחדת,לאומית,אישור הגעה,לאשר הגעה,תוצאות,מזכירה," +
                    "בית ספר,גן,מורה,גננת,בנק,ביטוח לאומי,עירייה,מס הכנסה",
                response = "Thank you. Please say the details and I will pass them to the owner.",
                responseHe = "תודה. אפשר לומר את הפרטים ואני אעביר לו אותם.",
                priority = 40,
            ),
            KeywordRule(
                intent = RuleIntent.CALLBACK_TIME,
                keywords = "call back,call me back,callback,when,what time,available,reach him,reach her,reach them,get back to me," +
                    "תחזור אליי,תחזור אלי,תחזרו אליי,תחזרו אלי,יחזור אליי,יחזור אלי,שיחזור,שיחזרו,לחזור אליי,לחזור אלי,יחזרו,מתי,באיזו שעה,באיזה שעה," +
                    "זמין,זמינה,להשיג,יתקשר,שיתקשר,שתתקשר,שיחזור אליי,שיחזיר צלצול,יחזיר צלצול,תחזיר צלצול,מתי אפשר,מתי הוא," +
                    "יחזיר לי,שיחזיר לי,תחזיר לי,יחזרו אליי,שיחזרו אליי,לחזור אליו,צלצול חוזר,שיחה חוזרת",
                response = "The owner will call you back as soon as possible. If there is a convenient time, please say it now.",
                responseHe = "הוא יחזור אליך בהקדם האפשרי. אם יש שעה שנוחה לך, אפשר לומר אותה עכשיו.",
                priority = 30,
            ),
            KeywordRule(
                intent = RuleIntent.TAKE_MESSAGE,
                keywords = "message,leave a message,tell him,tell her,tell them,let him know,let her know,let them know,pass on,remind," +
                    "הודעה,להשאיר הודעה,להשאיר לו,תגיד לו,תגידי לו,תגיד לה,תגידי לה,תמסור,תמסרי,למסור,תעביר לו,תעבירי לו,להעביר לו,תרשום,תרשמי," +
                    "תזכיר לו,תזכירי לו,שידע,שתדע,תודיע לו,תודיעי לו,רציתי להגיד,רציתי לומר,רציתי לעדכן,לעדכן אותו",
                response = "Of course. Please say your message now and I will save it for the owner.",
                responseHe = "בשמחה. אפשר לומר את ההודעה עכשיו, ואני אשמור אותה בשבילו.",
                priority = 20,
            ),
            KeywordRule(
                intent = RuleIntent.SALES,
                keywords = "offer,promotion,insurance,survey,special deal,discount,credit card,marketing," +
                    "מבצע,הצעה,הצעה מיוחדת,ביטוח,סקר,הנחה,כרטיס אשראי,הלוואה,פנסיה,משכנתא,שיווק,נציג מכירות,מבצע מיוחד,קרן השתלמות,החזר מס,סלקום,פרטנר,הוט,יס,בזק,חבילת גלישה",
                response = "Thank you. The owner does not take sales calls. Goodbye.",
                responseHe = "תודה. לא מעוניינים בשיחות מכירה. יום טוב.",
                priority = 15,
            ),
            KeywordRule(
                intent = RuleIntent.GOODBYE,
                keywords = "bye,goodbye,thanks,thank you,that's all,nothing else,no thanks," +
                    "ביי,ביי ביי,להתראות,יום טוב,ערב טוב,לילה טוב,זהו,זה הכל,זה הכול,אין צורך,לא צריך,סיימתי,תודה רבה,תודה,לא תודה,אין עוד,זה מספיק,שבוע טוב,שבת שלום",
                response = "Thank you. The owner will call you back. Goodbye.",
                responseHe = "תודה רבה. הוא יחזור אליך בהקדם. יום טוב!",
                priority = 8,
            ),
            KeywordRule(
                intent = RuleIntent.HELLO,
                keywords = "hello,hi,hey,good morning,good evening," +
                    "שלום,היי,הלו,אהלן,מה נשמע,מה שלומך,מה קורה,בוקר טוב,צהריים טובים,ערב טוב לך,אהלן אהלן,יו",
                response = "Hello! I am the digital assistant. The owner cannot answer right now. How can I help?",
                responseHe = "שלום! אני הנציג הדיגיטלי. הוא לא יכול לענות כרגע. במה אפשר לעזור?",
                priority = 5,
            ),
        )
    }
}
