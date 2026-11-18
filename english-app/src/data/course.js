// Original course content for Hebrew speakers learning English (A0–A2).
// Written for this app. Each lesson: words (en/he), sentences (en/he), optional Hebrew tip.
// Exercises are generated from this data by src/lesson-engine.js.

// Letters: [upper, Hebrew name (how to say it), example word, Hebrew meaning]
const ABC = [
  ['A', 'אֵיי', 'apple', 'תפוח'], ['B', 'בִּי', 'ball', 'כדור'], ['C', 'סִי', 'cat', 'חתול'], ['D', 'דִּי', 'dog', 'כלב'],
  ['E', 'אִי', 'egg', 'ביצה'], ['F', 'אֶף', 'fish', 'דג'], ['G', "גִ'י", 'girl', 'ילדה'], ['H', "אֵייץ'", 'hat', 'כובע'],
  ['I', 'אַיי', 'ice', 'קרח'], ['J', "גֵ'יי", 'juice', 'מיץ'], ['K', 'קֵיי', 'key', 'מפתח'], ['L', 'אֶל', 'lion', 'אריה'],
  ['M', 'אֶם', 'milk', 'חלב'], ['N', 'אֶן', 'nose', 'אף'], ['O', 'אוֹ', 'orange', 'תפוז'], ['P', 'פִּי', 'pen', 'עט'],
  ['Q', 'קְיוּ', 'queen', 'מלכה'], ['R', 'אַר', 'red', 'אדום'], ['S', 'אֶס', 'sun', 'שמש'], ['T', 'טִי', 'tree', 'עץ'],
  ['U', 'יוּ', 'umbrella', 'מטרייה'], ['V', 'וִי', 'violin', 'כינור'], ['W', 'דַּאבֶּל־יוּ', 'water', 'מים'], ['X', 'אֶקְס', 'box', 'קופסה'],
  ['Y', 'וַאי', 'yes', 'כן'], ['Z', 'זִי', 'zoo', 'גן חיות'],
].map(([L, name, word, he]) => ({ L, l: L.toLowerCase(), name, word, he, say: `the letter ${L}` }));
const pick = (...ls) => ls.map((x) => ABC.find((a) => a.L === x));
const letterLesson = (id, he, items, tip) => ({ id, he, kind: 'letters', tip, letters: items, words: items.map((i) => [i.word, i.he]), sentences: [] });
export const ALPHABET = ABC;

export const UNITS = [
  {
    id: 'abc', emoji: '🔤', he: 'האותיות באנגלית', en: 'The ABC', skippable: true,
    lessons: [
      letterLesson('abc1', 'אותיות A עד E', pick('A', 'B', 'C', 'D', 'E'), 'לכל אות יש שם (A = "אֵיי") וצליל. ב־apple שומעים "אֶ", אבל קוראים לאות "אֵיי".'),
      letterLesson('abc2', 'אותיות F עד J', pick('F', 'G', 'H', 'I', 'J'), 'G ו־J נשמעות דומה: G = "גִ\'י", J = "גֵ\'יי". כדאי לשים לב לסוף.'),
      letterLesson('abc3', 'אותיות K עד O', pick('K', 'L', 'M', 'N', 'O'), 'M ו־N מבלבלות: ל־M יש שתי "גבעות" (m), ל־N רק אחת (n).'),
      letterLesson('abc4', 'אותיות P עד T', pick('P', 'Q', 'R', 'S', 'T'), 'Q כמעט תמיד באה עם u: queen, question.'),
      letterLesson('abc5', 'אותיות U עד Z', pick('U', 'V', 'W', 'X', 'Y', 'Z'), 'W נקראת "דַּאבֶּל־יוּ" – כלומר "U כפולה". ו־X מופיעה בדרך כלל בסוף מילה: box, fox.'),
      letterLesson('abc6', 'כל האותיות – חזרה', pick('A', 'E', 'I', 'O', 'U', 'Y'), 'A, E, I, O, U הן "תנועות" (vowels) – הן כמו הניקוד בעברית.'),
    ],
  },
  {
    id: 'read', emoji: '📗', he: 'קוראים ראשונים', en: 'First reading', skippable: true,
    lessons: [
      letterLesson('read1', 'אותיות שמתבלבלים בהן', pick('B', 'D', 'P', 'Q', 'V', 'W'), 'b – קודם קו ואז בטן. d – קודם בטן ואז קו. במילה bed רואים מיטה: b בהתחלה, d בסוף.'),
      letterLesson('read2', 'צלילים של שתי אותיות', [
        { L: 'sh', l: 'sh', name: 'שׁ', word: 'ship', he: 'ספינה', say: 'ship' },
        { L: 'ch', l: 'ch', name: "צ'", word: 'chair', he: 'כיסא', say: 'chair' },
        { L: 'th', l: 'th', name: 'ת׳ (לשון בין השיניים)', word: 'three', he: 'שלוש', say: 'three' },
        { L: 'ph', l: 'ph', name: 'פ', word: 'phone', he: 'טלפון', say: 'phone' },
        { L: 'wh', l: 'wh', name: 'וו', word: 'what', he: 'מה', say: 'what' },
      ], 'שתי אותיות יחד יכולות ליצור צליל חדש: s + h = "שׁ".'),
      {
        id: 'read3', he: 'מילים ראשונות',
        tip: 'מילים קצרות נקראות אות אחרי אות: c-a-t = cat.',
        words: [['cat', 'חתול'], ['dog', 'כלב'], ['sun', 'שמש'], ['hat', 'כובע'], ['bed', 'מיטה'], ['cup', 'כוס']],
        sentences: [['a big cat', 'חתול גדול'], ['the sun is hot', 'השמש חמה'], ['my red hat', 'הכובע האדום שלי']],
      },
      {
        id: 'read4', he: 'עוד מילים קצרות',
        tip: 'a לפני מילה = "אחד/אחת", כמו a fox = שועל (אחד).',
        words: [['box', 'קופסה'], ['fox', 'שועל'], ['map', 'מפה'], ['leg', 'רגל'], ['nut', 'אגוז'], ['bag', 'תיק']],
        sentences: [['a fox in a box', 'שועל בקופסה'], ['I have a map', 'יש לי מפה'], ['my bag is big', 'התיק שלי גדול']],
      },
    ],
  },
  {
    id: 'u1', emoji: '👋', he: 'צעדים ראשונים', en: 'First steps',
    lessons: [
      {
        id: 'u1l1', he: 'שלום ולהתראות',
        tip: 'ב־Hi ו־Hello משתמשים כל היום. Good morning – רק עד הצהריים.',
        words: [['hello', 'שלום'], ['hi', 'היי'], ['bye', 'ביי'], ['good morning', 'בוקר טוב'], ['good night', 'לילה טוב'], ['thank you', 'תודה']],
        sentences: [['Hello, good morning', 'שלום, בוקר טוב'], ['Thank you, bye', 'תודה, ביי'], ['Hi, good night', 'היי, לילה טוב']],
      },
      {
        id: 'u1l2', he: 'נעים להכיר',
        tip: 'My name is… = קוראים לי… (מילולית: "השם שלי הוא").',
        words: [['name', 'שם'], ['my', 'שלי'], ['your', 'שלך'], ['what', 'מה'], ['is', 'הוא / היא / זה (פועל "להיות")'], ['nice to meet you', 'נעים להכיר']],
        sentences: [['What is your name', 'מה השם שלך'], ['My name is Dana', 'קוראים לי דנה'], ['Nice to meet you', 'נעים להכיר']],
      },
      {
        id: 'u1l3', he: 'מה שלומך?',
        tip: 'על How are you? עונים בקצרה: I am fine, thanks.',
        words: [['how', 'איך'], ['are', 'אתה / את / אתם (פועל "להיות")'], ['you', 'אתה / את'], ['fine', 'בסדר'], ['good', 'טוב'], ['and', 'ו־']],
        sentences: [['How are you', 'מה שלומך'], ['I am fine, thanks', 'אני בסדר, תודה'], ['I am good, and you', 'אני טוב, ואתה']],
      },
      {
        id: 'u1l4', he: 'כן, לא ובבקשה',
        tip: 'Please בבקשה כשמבקשים. You are welcome – בבקשה כתשובה ל"תודה".',
        words: [['yes', 'כן'], ['no', 'לא'], ['please', 'בבקשה (בבקשה ממך)'], ['sorry', 'סליחה / מצטער'], ['excuse me', 'סליחה (כדי לפנות)'], ['you are welcome', 'בבקשה (אין בעד מה)']],
        sentences: [['Yes, please', 'כן, בבקשה'], ['No, thank you', 'לא, תודה'], ['Excuse me, sorry', 'סליחה, מצטער']],
      },
    ],
  },
  {
    id: 'u2', emoji: '🙋', he: 'אני ואתה', en: 'Me and you',
    lessons: [
      {
        id: 'u2l1', he: 'אני, אתה, הוא, היא',
        tip: 'באנגלית אין זכר ונקבה ל־"you": אותה מילה ל"אתה", "את", "אתם" ו"אתן".',
        words: [['I', 'אני'], ['you', 'אתה / את'], ['he', 'הוא'], ['she', 'היא'], ['we', 'אנחנו'], ['they', 'הם / הן']],
        sentences: [['I am Noa', 'אני נועה'], ['He is Tom', 'הוא תום'], ['We are friends', 'אנחנו חברים']],
      },
      {
        id: 'u2l2', he: 'הפועל "להיות"',
        tip: 'בעברית אומרים "אני שמח". באנגלית חייבים פועל: I am happy.',
        words: [['am', 'הוא (אחרי I)'], ['happy', 'שמח'], ['tired', 'עייף'], ['hungry', 'רעב'], ['ready', 'מוכן'], ['busy', 'עסוק']],
        sentences: [['I am happy', 'אני שמח'], ['She is tired', 'היא עייפה'], ['They are busy', 'הם עסוקים']],
      },
      {
        id: 'u2l3', he: 'מאיפה אתה?',
        tip: 'שמות של ארצות ושפות תמיד באות גדולה: Israel, English.',
        words: [['from', 'מ־'], ['where', 'איפה / מאיפה'], ['Israel', 'ישראל'], ['country', 'מדינה / ארץ'], ['city', 'עיר'], ['live', 'גר']],
        sentences: [['Where are you from', 'מאיפה אתה'], ['I am from Israel', 'אני מישראל'], ['I live in a big city', 'אני גר בעיר גדולה']],
      },
      {
        id: 'u2l4', he: 'שאלות כן ולא',
        tip: 'כדי לשאול, מקדימים את הפועל: You are ready → Are you ready?',
        words: [['not', 'לא (בתוך משפט)'], ['student', 'סטודנט / תלמיד'], ['teacher', 'מורה'], ['here', 'כאן'], ['there', 'שם'], ['now', 'עכשיו']],
        sentences: [['Are you a student', 'אתה סטודנט'], ['I am not a teacher', 'אני לא מורה'], ['Is she here now', 'היא כאן עכשיו']],
      },
    ],
  },
  {
    id: 'u3', emoji: '👨‍👩‍👧', he: 'משפחה', en: 'Family',
    lessons: [
      {
        id: 'u3l1', he: 'בני המשפחה',
        words: [['mother', 'אמא'], ['father', 'אבא'], ['sister', 'אחות'], ['brother', 'אח'], ['family', 'משפחה'], ['parents', 'הורים']],
        sentences: [['This is my mother', 'זאת אמא שלי'], ['I have a brother', 'יש לי אח'], ['My family is big', 'המשפחה שלי גדולה']],
        tip: 'יש לי = I have. לא אומרים "There is to me".',
      },
      {
        id: 'u3l2', he: 'ילדים וסבים',
        words: [['son', 'בן'], ['daughter', 'בת'], ['child', 'ילד'], ['children', 'ילדים'], ['grandmother', 'סבתא'], ['grandfather', 'סבא']],
        sentences: [['She has two children', 'יש לה שני ילדים'], ['My grandmother is old', 'סבתא שלי זקנה'], ['He is my son', 'הוא הבן שלי']],
        tip: 'עם he / she הפועל מקבל s: I have → she has.',
      },
      {
        id: 'u3l3', he: 'תיאור אנשים',
        words: [['tall', 'גבוה'], ['short', 'נמוך / קצר'], ['young', 'צעיר'], ['old', 'זקן / ישן'], ['kind', 'נחמד / טוב לב'], ['funny', 'מצחיק']],
        sentences: [['My father is tall', 'אבא שלי גבוה'], ['My sister is funny', 'אחותי מצחיקה'], ['They are young and kind', 'הם צעירים ונחמדים']],
        tip: 'שם התואר לא משתנה: tall boy, tall girls – תמיד tall.',
      },
    ],
  },
  {
    id: 'u4', emoji: '🍽️', he: 'אוכל ושתייה', en: 'Food and drink',
    lessons: [
      {
        id: 'u4l1', he: 'ארוחת בוקר',
        words: [['bread', 'לחם'], ['egg', 'ביצה'], ['cheese', 'גבינה'], ['coffee', 'קפה'], ['tea', 'תה'], ['water', 'מים']],
        sentences: [['I want coffee', 'אני רוצה קפה'], ['Bread and cheese, please', 'לחם וגבינה, בבקשה'], ['I drink water', 'אני שותה מים']],
        tip: 'a לפני מילה שמתחילה בעיצור (a coffee), an לפני תנועה (an egg).',
      },
      {
        id: 'u4l2', he: 'פירות וירקות',
        words: [['apple', 'תפוח'], ['banana', 'בננה'], ['tomato', 'עגבנייה'], ['salad', 'סלט'], ['orange', 'תפוז'], ['vegetables', 'ירקות']],
        sentences: [['I like apples', 'אני אוהב תפוחים'], ['This salad is good', 'הסלט הזה טוב'], ['Do you want an orange', 'אתה רוצה תפוז']],
        tip: 'בשאלות עם פועל רגיל משתמשים ב־Do: Do you want…?',
      },
      {
        id: 'u4l3', he: 'במסעדה',
        words: [['menu', 'תפריט'], ['table', 'שולחן'], ['bill', 'חשבון'], ['delicious', 'טעים מאוד'], ['order', 'להזמין'], ['waiter', 'מלצר']],
        sentences: [['A table for two, please', 'שולחן לשניים, בבקשה'], ['Can I have the bill', 'אפשר את החשבון'], ['The food is delicious', 'האוכל טעים מאוד']],
        tip: 'Can I have…? היא הדרך המנומסת לבקש משהו.',
      },
    ],
  },
  {
    id: 'u5', emoji: '🔢', he: 'מספרים וזמן', en: 'Numbers and time',
    lessons: [
      {
        id: 'u5l1', he: 'מספרים 1–10',
        words: [['one', 'אחת'], ['two', 'שתיים'], ['three', 'שלוש'], ['four', 'ארבע'], ['five', 'חמש'], ['ten', 'עשר']],
        sentences: [['I have two sisters', 'יש לי שתי אחיות'], ['Three coffees, please', 'שלושה קפה, בבקשה'], ['She is five', 'היא בת חמש']],
        tip: 'three מתחיל ב־th: מניחים את הלשון בין השיניים ונושפים. לא "טרי" ולא "פרי".',
      },
      {
        id: 'u5l2', he: 'ימי השבוע',
        words: [['Sunday', 'יום ראשון'], ['Monday', 'יום שני'], ['Friday', 'יום שישי'], ['Saturday', 'שבת'], ['today', 'היום'], ['tomorrow', 'מחר']],
        sentences: [['Today is Sunday', 'היום יום ראשון'], ['See you tomorrow', 'נתראה מחר'], ['I work on Monday', 'אני עובד ביום שני']],
        tip: 'ימי השבוע תמיד באות גדולה, ולפניהם on: on Friday.',
      },
      {
        id: 'u5l3', he: 'מה השעה?',
        words: [['time', 'זמן / שעה'], ['hour', 'שעה (60 דקות)'], ['minute', 'דקה'], ['morning', 'בוקר'], ['evening', 'ערב'], ['late', 'מאוחר']],
        sentences: [['What time is it', 'מה השעה'], ['It is five o\'clock', 'השעה חמש'], ['I am late', 'אני מאחר']],
        tip: 'hour מתחיל בצליל תנועה (ה־h שקטה), לכן an hour.',
      },
    ],
  },
  {
    id: 'u6', emoji: '🏙️', he: 'בעיר', en: 'In the city',
    lessons: [
      {
        id: 'u6l1', he: 'מקומות',
        words: [['street', 'רחוב'], ['shop', 'חנות'], ['bank', 'בנק'], ['hospital', 'בית חולים'], ['school', 'בית ספר'], ['park', 'פארק']],
        sentences: [['Where is the bank', 'איפה הבנק'], ['The shop is on this street', 'החנות ברחוב הזה'], ['The park is near the school', 'הפארק ליד בית הספר']],
        tip: 'the = ה׳ הידיעה. the bank = הבנק.',
      },
      {
        id: 'u6l2', he: 'תחבורה',
        words: [['bus', 'אוטובוס'], ['train', 'רכבת'], ['car', 'מכונית'], ['taxi', 'מונית'], ['station', 'תחנה'], ['ticket', 'כרטיס']],
        sentences: [['I take the bus', 'אני נוסע באוטובוס'], ['Where is the train station', 'איפה תחנת הרכבת'], ['One ticket, please', 'כרטיס אחד, בבקשה']],
        tip: 'ב־train station המילה הראשונה מתארת את השנייה – הפוך מעברית ("תחנת רכבת").',
      },
      {
        id: 'u6l3', he: 'הכוונה',
        words: [['left', 'שמאלה'], ['right', 'ימינה'], ['straight', 'ישר'], ['near', 'קרוב / ליד'], ['far', 'רחוק'], ['turn', 'לפנות']],
        sentences: [['Turn left here', 'תפנה כאן שמאלה'], ['Go straight', 'סע ישר'], ['Is it far', 'זה רחוק']],
        tip: 'right זה גם "ימינה" וגם "נכון": You are right = אתה צודק.',
      },
    ],
  },
  {
    id: 'u7', emoji: '💼', he: 'עבודה ולימודים', en: 'Work and study',
    lessons: [
      {
        id: 'u7l1', he: 'במשרד',
        words: [['work', 'עבודה / לעבוד'], ['office', 'משרד'], ['meeting', 'פגישה'], ['email', 'אימייל'], ['computer', 'מחשב'], ['boss', 'בוס / מנהל']],
        sentences: [['I have a meeting today', 'יש לי פגישה היום'], ['I work in an office', 'אני עובד במשרד'], ['Please send me an email', 'בבקשה שלח לי אימייל']],
        tip: 'an office ולא a office – כי office מתחיל בתנועה.',
      },
      {
        id: 'u7l2', he: 'בכיתה',
        words: [['book', 'ספר'], ['pen', 'עט'], ['question', 'שאלה'], ['answer', 'תשובה'], ['learn', 'ללמוד'], ['homework', 'שיעורי בית']],
        sentences: [['I have a question', 'יש לי שאלה'], ['I learn English', 'אני לומד אנגלית'], ['Where is my book', 'איפה הספר שלי']],
        tip: 'homework לא מקבל s ולא a: much homework, לא homeworks.',
      },
      {
        id: 'u7l3', he: 'עכשיו – ing',
        words: [['working', 'עובד (עכשיו)'], ['reading', 'קורא (עכשיו)'], ['writing', 'כותב (עכשיו)'], ['eating', 'אוכל (עכשיו)'], ['sleeping', 'ישן (עכשיו)'], ['waiting', 'מחכה (עכשיו)']],
        sentences: [['I am working now', 'אני עובד עכשיו'], ['She is reading a book', 'היא קוראת ספר'], ['We are waiting for the bus', 'אנחנו מחכים לאוטובוס']],
        tip: 'לפעולה שקורית ממש עכשיו: am / is / are + פועל עם ing.',
      },
    ],
  },
  {
    id: 'u8', emoji: '✈️', he: 'טיול לחו"ל', en: 'Travel',
    lessons: [
      {
        id: 'u8l1', he: 'בשדה התעופה',
        words: [['airport', 'שדה תעופה'], ['flight', 'טיסה'], ['passport', 'דרכון'], ['bag', 'תיק'], ['gate', 'שער'], ['delay', 'עיכוב']],
        sentences: [['Here is my passport', 'הנה הדרכון שלי'], ['My flight is late', 'הטיסה שלי מאחרת'], ['Where is gate five', 'איפה שער חמש']],
        tip: 'Here is… = הנה… כשנותנים משהו למישהו.',
      },
      {
        id: 'u8l2', he: 'במלון',
        words: [['hotel', 'מלון'], ['room', 'חדר'], ['key', 'מפתח'], ['night', 'לילה'], ['reservation', 'הזמנה'], ['breakfast', 'ארוחת בוקר']],
        sentences: [['I have a reservation', 'יש לי הזמנה'], ['A room for two nights', 'חדר לשני לילות'], ['Is breakfast included', 'ארוחת הבוקר כלולה']],
        tip: 'Reservation זו הזמנה של מקום. Order – הזמנה של אוכל או מוצר.',
      },
      {
        id: 'u8l3', he: 'קניות',
        words: [['how much', 'כמה (עולה)'], ['price', 'מחיר'], ['cheap', 'זול'], ['expensive', 'יקר'], ['money', 'כסף'], ['card', 'כרטיס אשראי']],
        sentences: [['How much is this', 'כמה זה עולה'], ['It is too expensive', 'זה יקר מדי'], ['Can I pay by card', 'אפשר לשלם בכרטיס']],
        tip: 'too = מדי. too expensive = יקר מדי.',
      },
    ],
  },
  {
    id: 'u9', emoji: '🎯', he: 'מלכודות לדוברי עברית', en: 'Hebrew speaker traps',
    lessons: [
      {
        id: 'u9l1', he: 'th, w ו־v',
        tip: 'W – שפתיים עגולות כמו "וו" רכה (what). V – שיניים עליונות על השפה התחתונה (very). Th – לשון בין השיניים.',
        words: [['think', 'לחשוב'], ['thanks', 'תודה'], ['this', 'זה'], ['very', 'מאוד'], ['water', 'מים'], ['west', 'מערב']],
        sentences: [['I think this is very good', 'אני חושב שזה טוב מאוד'], ['Thanks for the water', 'תודה על המים'], ['We live in the west', 'אנחנו גרים במערב']],
      },
      {
        id: 'u9l2', he: 'מילים שמבלבלות',
        tip: 'Actually = בעצם (לא "אקטואלי"). Sympathetic = מלא אהדה (לא "סימפטי" = nice). Pension = קצבת פנסיה (לא צימר).',
        words: [['actually', 'בעצם'], ['nice', 'סימפטי / נחמד'], ['current', 'אקטואלי / עדכני'], ['pension', 'פנסיה (קצבה)'], ['guest house', 'פנסיון / בית הארחה'], ['sympathetic', 'מבין ותומך']],
        sentences: [['Actually, he is very nice', 'בעצם, הוא מאוד סימפטי'], ['This is current news', 'אלה חדשות אקטואליות'], ['We stay in a guest house', 'אנחנו לנים בפנסיון']],
      },
      {
        id: 'u9l3', he: 'סדר המילים',
        tip: 'בעברית "ספר אדום", באנגלית שם התואר לפני: a red book. ושאלות מתחילות ב־Do / Is.',
        words: [['red', 'אדום'], ['blue', 'כחול'], ['big', 'גדול'], ['small', 'קטן'], ['new', 'חדש'], ['house', 'בית']],
        sentences: [['I have a red car', 'יש לי מכונית אדומה'], ['This is a big house', 'זה בית גדול'], ['Do you like my new bag', 'אתה אוהב את התיק החדש שלי']],
      },
    ],
  },
];

export function allLessons() {
  return UNITS.flatMap((u) => u.lessons.map((l) => ({ ...l, unitId: u.id })));
}

export function findLesson(id) {
  return allLessons().find((l) => l.id === id) || null;
}

export function allWords() {
  const seen = new Map();
  for (const l of allLessons()) for (const [en, he] of l.words) if (!seen.has(en)) seen.set(en, { en, he, lessonId: l.id });
  return [...seen.values()];
}
