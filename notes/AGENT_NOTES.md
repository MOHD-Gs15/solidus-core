# AGENT NOTES — Solidus 2.1.4 Implementation Notes

> ملاحظات منفصلة من الوكيل المنفّذ — للمراجعة التقنية وقرارات التصميم
> والمخاطر المتبقية واختبارات القبول المقترحة. ليست بديلاً عن
> `docs/FEATURES_TRADE_BIDDING.md` (التوثيق الرسمي) بل مكمّلة له.

---

## 1) ملخص سريع لما تم

| المهمة | الحالة |
| --- | --- |
| الإضافة 1 — المزايدة (Bidding) للمزاد | ✅ منفّذة بالكامل (escrow + anti-snipe + استرداد ذاتي + GUI + أوامر) |
| الإضافة 2 — واجهة /trade | ✅ منفّذة بالكامل (معاينة متبادلة + عناصر + مال + موافقة الطرفين) |
| الإضافة 3 — MySQL/MariaDB/Redis | 📋 خطة تفصيلية فقط كما طُلب: `docs/DB_SCALING_PLAN.md` |
| البناء | ✅ `compileJava` ناجح (Java 25 / Gradle 9.5.1) |
| الاختبارات | ✅ 309+ اختبار ناجح، منها ~25 اختباراً جديداً |

**قرار الإصدار (مُحدّث بقرار المالك 2026-09-06)**: الترقيم النهائي هو
`2.1.4` داخل عائلة `2.1.x` — الإضافات (أوامر `/trade` والمزايدة) **إضافية
بحتة** (additive) ولا تكسر توافق الماودات المرافقة بفضل آلية `fromCode`
الآمنة، لذا لا تستدعي قفزة عائلة. كانت قد رُقّمت مؤقتاً `2.2.0` ثم أُعيد
الترقيم بقرار المالك؛ عائلة `2.2.x` **محجوزة** حصرياً لعصر المالتي
سيرفر/إعادة هيكلة التخزين القادمة (انظر `VERSIONING.md`).

---

## 2) قرارات تصميم مهمة ولماذا

### أ) الضمان (Escrow) بحساب نظامي وليس "خصم عند الفوز"
- **المشكلة**: لو خُصم المال عند الفوز فقط، قد يفوز لاعب لا يملك المال →
  فشل البيع وعقاب للبائع (نصب عكسي).
- **الحل المتبع**: الخصم فور المزايدة إلى حساب ضمان نظامي (`EscrowAccount`
  — UUID صفري)، والإرجاع الفوري عند التجاوز/الإلغاء/الشراء الفوري.
- **لماذا هذا هو المعيار**: نفس نموذج Crazy Auctions وAuctionHouse — طلب
  المستخدم صراحة "غش من الإضافات الأخرى".
- **كل حركة مال** تمر عبر `transferAtomicWithLedger` (آلية 2.1.3 المتصلبة)
  فتكون كل حركة ذرية مع دليلها في السجل داخل نفس المعاملة.

### ب) حالة المزايدة في جدول منفصل وليس أعمدة جديدة
- `auction_bid_state` منفصل عن `auction_listings` → `AuctionEntry` record
  لم يتغير → **صفر كسر** للاختبارات والمستهلكين الحاليين.
- قائمة بلا صف حالة مزايدة = قائمة buy-now عادية كما كانت تماماً.

### ج) التداول بحاويتين متطابقتين (mirrored) وليس حاوية مشتركة
- كل لاعب يرى عناصره يساراً وعناصر شريكه يميناً (نمط TradeMe).
- المرآة تتزامن يدوياً + `broadcastFullState()` بعد كل تغيير (ضمان PR#13
  ضد الأشباح يمتد لنافذة التداول).

### د) جلسة التداول خالية من كائنات اللاعب
- `TradeSession` تحمل UUIDs وأسماء فقط، والمدير يفكّ اللاعب الحي عند الحاجة.
- السبب العملي: Mockito لا يستطيع محاكاة `ServerPlayer` (لا يمكن تهيئة
  تسلسلها خارج بيئة اللعبة) — فأعدت التصميم ليكون قابلاً للاختبار الوحدوي.
- فائدة إضافية: لا مراجع معلقة (stale) بعد انقطاع الاتصال.

### هـ) مال التداول يمر عبر `transferOffline` الحالي
- يحصل مجاناً على: حمولات Governance (allowTransfer/afterTransfer)، حدود
  التحويل، الضرائب — دون إضافة أي واجهة hooks جديدة.
- السلوك المقصود: تداول المال يُحتسب ضمن حدود التحويل اليومية.

---

## 3) المخاطر المتبقية والنوافذ النظرية (بصراحة)

1. **نافذة crash بين خصم الضمان وتسجيل المزايدة** (ملي ثوانٍ): لو انهار
   الخادم بين الخطوتين، المال يبقى في حساب الضمان مع صف `BID_PLACED` في
   السجل دون تغيير حالة المزايدة. الفحص الدوري عند الإقلاع
   (`checkEscrowConsistency`) ينبه بأي فرق — **يُصحّح يدوياً** عبر السجل.
   اعتبرت أن إضافة آلية ترحيل كاملة لهذه النافذة الضئيلة لا تستحق التعقيد
   في هذا الإصدار (نفس مستوى تحمّل النوافذ الذي كان في المشروع قبل 2.1.3).

2. **عناصر جلسة التداول في الذاكرة** (نمط SellContainer نفسه): لو انهار
   الخادم **أثناء** نافذة تداول مفتوحة، العناصر المعروضة ضاعت (لم تُحفظ).
   نفس التعرض الموجود أصلاً في واجهة البيع — وثّقته لأن نافذة التداول أطول.
   العلاج المستقبلي: حفظ الجلسات في DB عند كل تغيير (جداول trade_session /
   trade_items) — سهل الإضافة لاحقاً بنفس أنماط الكود الحالية.

3. **سعة حساب الضمان**: `MAX_BALANCE = 100,000,000` يطبّق على حساب الضمان
   أيضاً؛ لو تراكمت مزايدات ضخمة جداً بشكل متزامن (>100M مجموعاً) سيرفض
   الضمان برسالة واضحة للاعب. غير واقعي عملياً، لكنه موثّق هنا وفي اختبار
   `escrowOverflowProtected`.

4. **عناصر التداول بلا Blacklist**: أي آيتم يمكن عرضه في التداول (بما فيها
   shulkers بداخلها ما تشاء). إن أردت منع نشر آيتمات معينة أضف قائمة سوداء
   في `TradeScreenHandler.handleMyOfferSlotClick` — نقطة مركزية واحدة.

5. **حقل `itemNbt` في `storeWonItemRowFromDelivery` يُخزن NULL** عند فشل
   التسليم المباشر للفائز الذي انقطع بين اللقطة والتسليم — يستعاد العنصر
   من المادة الأساسية فقط عند `/ah collect` (قد يفقد enchanments في هذه
   الحالة الاستثنائية الضيقة جداً). حالات أخرى تحفظ NBT كاملاً.

6. **Chat prompt يعتمد `ServerMessageEvents.ALLOW_CHAT_MESSAGE`** من fabric
   message API v1 — اختبرته بالترجمة فقط، وليس بسيرفر حي. إن اختلفت سلوكيات
   توقيع الرسائل في 26.1.x فالأمر معزول في `chat/ChatPrompts.java` فقط.

---

## 4) اختبار قبول مقترح على سيرفر حي (سريع)

**المزايدة:**
1. لاعبان A وB. A: `/ah sell 5000 500` → يجب ظهور BIDDING ENABLED في GUI.
2. B ينقر يميناً على العنصر → يغلق GUI ويطلب المبلغ في الشات. اكتب `600`.
3. تحقق: رسالة نجاح، `/balance` انخفض 600، `/transactions` يظهر BID.
4. A يشتري buy-now الآن → يصل B إشعار استرداد 600 فوراً (`BID+` في السجل).
5. كرر ودع المزاد ينتهي بمزايدة B → B يستلم العنصر (أو عبر `/ah collect`
   لو كان أوفلاين) وA يستلم 600.
6. تجاوز سريع في آخر 10 دقائق → يجب تمديد الوقت 5 دقائق.

**التداول:**
1. لاعبان متجاوران: `/trade B` ثم B: `/trade accept` → نافذتان.
2. A يضع آيتم + يضبط مالاً 100 (زر الذهب ثم الكتابة في الشات).
3. B يرى التحديث فورياً يميناً. كلاهما READY → التنفيذ فوري.
4. تحقق: تبادل العناصر، تحويل 100، صفوف `TRD-`/`TRD+` في `/transactions`.
5. بينما الجلسة مفتوحة: أي تعديل من A بعد ضغط READY → يلغي جاهزية الطرفين.
6. افصل اتصال A أثناء الجلسة → تعود عناصر B إليه فوراً.
7. جرب ESC أثناء الجلسة → إلغاء وعودة العناصر (لا شيء يقع أرضاً).

---

## 5) ملاحظات صيانة لاحقة

- **الثوابت القابلة للضبط** كلها في `BidRules` و`TradeManager` و`ChatPrompts`
  (النسب، النوافذ، المسافة). أفضل خطوة تالية: نقلها إلى `storage.json` مع
  hot-reload مثل `listingFee`.
- **إضافة أنواع سجل جديدة مستقبلاً**: لا تنسَ تحديث switchين في
  `TransactionsCommand.formatTransactionEntry` (الترجمة ستفشل بوضوح إن نُسيت
  — الـ switch مغطي بالكامل).
- **`getMaterialName`** يرجع UPPERCASE — أسماء العناصر في رسائل التداول
  تتبع نفس عرف المشروع.
- **اختبارات مستقبلية**: انسخ نمط `BidEscrowFlowTest` (SQLiteStorage فقط)
  لأي منطق مال جديد، ونمط `TradeSessionStateTest` (دومين نقي) لأي حالة
  جلسة جديدة. تجنّب أي test يلمس `ItemStack`/`Items` مباشرة — تتطلب
  bootstrap اللعبة.
- **الضمان وbaltop**: إن أضفت أوامر إحصاء/ترتيب جديدة، استثنِ
  `EscrowAccount.UUID_ZERO` منها (نفس نمط `getTopBalances`).

---

## 6) ملفات جديدة/معدلة (مرجع سريع)

**جديدة:**
```
src/main/java/com/solidus/auction/BidRules.java        قواعد المزايدة النقية
src/main/java/com/solidus/auction/BidState.java        سجل حالة المزايدة
src/main/java/com/solidus/economy/EscrowAccount.java   حساب الضمان النظامي
src/main/java/com/solidus/chat/ChatPrompts.java        مطالبات الشات (مزايدة/مال)
src/main/java/com/solidus/trade/TradeManager.java      منسق التداول
src/main/java/com/solidus/trade/TradeSession.java      آلة حالة الجلسة
src/main/java/com/solidus/trade/TradeContainer.java    حاوية الجلسة
src/main/java/com/solidus/trade/TradeGUI.java          تخطيط النافذة
src/main/java/com/solidus/trade/TradeScreenHandler.java معالج النقرات
src/main/java/com/solidus/commands/TradeCommand.java   أمر /trade
src/test/java/com/solidus/auction/BidEscrowFlowTest.java
src/test/java/com/solidus/trade/TradeSessionStateTest.java
docs/FEATURES_TRADE_BIDDING.md    التوثيق الرسمي للميزتين
docs/DB_SCALING_PLAN.md           خطة MySQL/MariaDB/Redis (الإضافة 3)
notes/AGENT_NOTES.md              هذا الملف
```

**معدلة:**
```
SolidusMod.java            ربط ChatPrompts + TradeManager + تنظيف الانقطاع
AuctionManager.java        جداول المزايدة + placeBid + تسوية الفوز + السواتب
AuctionGUI.java            عرض معلومات المزايدة + attachBidStatesAndBuild
AuctionScreenHandler.java  النقر الأيمن → مطالبة المزايدة
AuctionCommand.java        /ah bid + /ah sell <price> <startbid>
TransactionLog.java        الأنواع الجديدة BID_*/AUCTION_WON/TRADE_*
SQLiteStorage.java         استثناء الضمان من baltop (+fallback)
EconomyEngine.java         إنشاء حساب الضمان برصيد صفر
BalanceManager.java        (لم يتغير فعلياً — الفحص فقط)
SolidusPermissions.java    AUCTION_BID + TRADE
TransactionsCommand.java   تغطية الأنواع الجديدة في switch
PacketHandler.java         توجيه نقرات TradeScreenHandler + hasSolidusScreenOpen
gradle.properties          mod_version = 2.1.4
README.md                  أوامر جديدة في جدول الأوامر
```

---

## 9) ملاحظات إصدار 2.2.0 — مرحلتا التخزين (DB Scaling Phase 1+2)

**ما نُفّذ (2.1.5 ثم 2.2.0)**: واجهة `StorageBackend` (refactor نقي، الـ 326
اختبار بقت خضراء)، ثم `MySqlStorage` كامل: HikariCP، `DECIMAL(18,2)` مع غلاف
`Money` (BigDecimal)، أقفال `FOR UPDATE` بترتيب UUID التصاعدي الحتمي، إعادة
محاولة deadlock (1213/1205) مرتين مع backoff، تحديثات CAS، صفوف الـ ledger داخل
معاملة التحويل، قراءات database-first (الكاش fallback فقط — لا يظهر رصيد قديم
بين سيرفرين)، فشل مغلق عند بدء التشغيل بدون قاعدة بيانات، `TransactionLog`
مدرك للهجتين (DDL MySQL + اتصالات pooled)، `storage.json` مع تجاوز كلمة المرور
من `SOLIDUS_DB_PASSWORD`، وملف `docs/sql/mysql/001_init.sql` (يشمل جداول
المزادات الاستباقية).

**قرارات جوهرية**:
1. الأنواع المتداخلة (`TransferOutcome`...) بقيت على `SQLiteStorage` عمداً —
   `SolidusAPI` يعرضها للماودات المرافقة وقاعدة المالك تمنع كسرها في عائلة
   patch. الاستخراج للـ top-level مؤجل لقفزة عائلة (غير ضروري للواجهة).
2. الكاش في وضع MySQL ليس مصدراً أبداً — كل قراءة رصيد تلمس القاعدة (بشبكة،
   كاش قديم = ثغرة ازدواج). Redis L1/L2 في 2.2.1 هو من سيحسن القراءة بأمان.
3. فشل الاتصال عند الإقلاع = توقف واضح (fail closed) — سيرفر شبكة لا يجب أن
   يقلع باقتصاد خاطئ؛ وفشل القراءة أثناء التشغيل = قيمة الكاش (لا صفر أبداً).

**حدود النطاق الصادقة (موجودة أيضاً في DB_SCALING_PLAN §11)**:
- المزادات ما زالت SQLite محلية في 2.2.0 (أرجل المال تمر عبر القاعدة المشتركة
  أصلاً) — نقل `AuctionStore` = 2.2.1.
- أمر `/solidus-admin storage migrate` غير منفذ — الترحيل اليدوي SQL حتى 2.2.1.
- جدول `operations` منشأ لكن غير موصول بالعمليات بعد (2.2.1 مع Redis).
- اختبارات MySQL الحقيقية (عقد + سباق تزامن بين نسختين) محمولة على CI وتُتخطى
  ذاتياً بدون `SOLIDUS_TEST_MYSQL_HOST` — البنية هنا بلا Docker؛ يجب تشغيلها
  مرة واحدة على CI أو ضد MariaDB حقيقية قبل الإنتاج (قائمة القبول أدناه).

**قائمة قبول مقترحة قبل تشغيل شبكة إنتاجية**:
- [ ] CI مع خدمة MariaDB 10.11 → `SOLIDUS_TEST_MYSQL_HOST` → العقد + السباق أخضر.
- [ ] تجربة سيرفرين محليين على نفس القاعدة: دفع متقاطع + /baltop موحد.
- [ ] ترحيل بيانات تجريبي (SQLite → MySQL يدوياً) ومطابقة `SUM(balance)` للسنت.
- [ ] جدار ناري: قاعدة البيانات على شبكة خاصة فقط؛ TLS عبر `useSsl=true`.
