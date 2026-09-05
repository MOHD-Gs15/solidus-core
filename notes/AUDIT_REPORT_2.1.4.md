# AUDIT REPORT — Documentation Accuracy Audit (2.1.4)

> **ملاحظة إعادة الترقيم (2026-09-06):** الإصدار الذي يوثّقه هذا التقرير رُقّم
> عند صدوره مؤقتاً `2.2.0`، ثم أعاد المالك الترقيم إلى `2.1.4` (عائلة
> `2.2.x` محجوزة حصرياً لعصر المالتي سيرفر — انظر `VERSIONING.md`).
> أسماء الإصدارات أدناه محدثة وفقاً لذلك.

> تدقيق توثيق شامل: قراءة كل ملف كود (~17,440 سطر Java) وكل ملف توثيق ومقابلة كل
> ادعاء بالكود الفعلي. التاريخ: 2026-09-05. المنهج: **لا اختلاق** — كل تصحيح
> أدناه مثبت بملف وسطر في الكود.

---

## 1) المنهجية

- قرأت **كل** ملفات `src/main/java` (47 ملفاً) + `fabric.mod.json` +
  `gradle.properties` + `build.gradle` + `shop.json` + عدّ الاختبارات من
  `src/test` (309 `@Test`).
- قابلت كل ادعاء في: README.md, VERSIONING.md, docs/ARCHITECTURE.md (2004
  سطر), docs/FEATURES_TRADE_BIDDING.md, docs/DB_SCALING_PLAN.md,
  docs/MONEY_ROUNDING.md, notes/AGENT_NOTES.md, notes/CHANGES.md.

## 2) حكم على كل وثيقة

| الوثيقة | الحكم قبل التدقيق | ما تم |
| --- | --- | --- |
| `docs/FEATURES_TRADE_BIDDING.md` | ✅ دقيقة (مطابقة للكود بنداً بنداً) | لم تُغيّر |
| `docs/DB_SCALING_PLAN.md` | ✅ خطة، ادعاءاتها عن الكود صحيحة | لم تُغيّر |
| `notes/AGENT_NOTES.md` | ✅ دقيقة | لم تُغيّر |
| `notes/CHANGES.md` / `commit_msg.txt` | سجل تاريخي (2.1.0) | لم يُغيّر — سجل زمني |
| `README.md` | ❌ قديم + ميزة وهمية | أُصلح |
| `docs/ARCHITECTURE.md` | ❌ قديم بكثافة | أُصلح (~30 موضعاً) |
| `VERSIONING.md` | ❌ يقول العائلة 2.1.0 | أُصلح إلى 2.1.4 |
| `docs/MONEY_ROUNDING.md` | ⚠️ سطر واحد غير دقيق | أُصلح |

## 3) أهم الاكتشافات (الأخطأ إلى الأصح)

1. **ميزة "anti-farm reduction" الوهمية (README)** — الأهم: README كان يصف
   قسمين + FAQ كامل عن "تخفيض تلقائي بالنسبة المئوية لأسعار بيع الموارد
   المزرعية". **لا يوجد أي منطق كهذا في الكود** (بحث شامل: لا reduction /
   percentage / discount / multiplier في مسارات البيع). الواقع: أسعار
   البيع/الشراء يدوية لكل مادة في `shop.json` (`buy-price`/`sell-price`) —
   تحكم مشغّل كامل بلا مضاعفات خفية. صُحح القسمان + FAQ + نقاط الأقسام.
2. **`ScreenHandlerMixin` غير موجود** — ARCHITECTURE §10.2 يوثق Mixin كامل
   بكود عينة، لكن `solidus.mixins.json` يسجل **ServerPlayerEntityMixin فقط**.
   الحماية الفعلية: تحقق containerId في الـMixin + `broadcastFullState()` بعد
   كل نقرة + `DisplaySlot` (gui/DisplaySlot.java) + فحوص الملكية داخل
   handlers. استُبدل القسم بـ"ScreenHandler-Level Protections (No Second
   Mixin)" وصُححت الطبقات في §2.5 و§12.2 و§10.1.
3. **`broadcastChanges()` → `broadcastFullState()`** — عينة الكود في §10.1
   كانت تعرض السياسة القديمة؛ الفعلي: PacketHandler يعمل full resync بعد كل
   نقرة معالجة + resync مقيّد (1/200ms) للنقرات المرفوضة (مضاد التضخيم).
4. **جدول الصلاحيات خاطئ بالكامل** — النمط الفعلي `solidus.command.*`
   (SolidusPermissions.java) وليس `solidus.core.<x>.<y>`. أُعيد بناء الجدول
   بعقد الأوامر الـ18 الحقيقية + الافتراضيات الفعلية للمودات المرافقة.
5. **أنواع المعاملات**: القديم 10 أنواع مرقمة؛ الفعلي **15 نوعاً TEXT** بعد
   إضافة BID_PLACED/BID_REFUNDED/AUCTION_WON/TRADE_SEND/TRADE_RECEIVE.
   صححت §6.4 وقائمة §15.
6. **مخطط قاعدة البيانات في §15 كان ناقصاً 3 جداول** — أضفت
   `auction_bid_state` + `auction_bids` (+فهرسها) + `auction_won_items`
   (+فهرسها) و`settled_reason = WON`.
7. **صيغة shop.json في §16 كانت خاطئة بالكامل** — الفعلي: كائن واحد،
   `sections` خريطة كائنات وليست مصفوفة، لا حقل `id` (المفتاح هو المعرف)،
   `display_name` كائن JSON وليس نصاً مهرّباً، `items` خريطة مفاتيح رقمية،
   المفاتيح `buy-price`/`sell-price` بشرطة، الأيقونات بأسماء خاملة بلا بادئة
   `minecraft:`. كما أضفت الجدول بالمفاتيح العلوية
   `startingBalance`/`currency`/`listingFee` (تطبقها `applyGlobalSettings`).
8. **Hot reload**: §16 كانت تقول "future enhancement" — لكن `/shop reload`
   (OP 2) موجود فعلاً في ShopCommand. صُحح.
9. **`/ah search <term>` غائب عن كل التوثيق** — موجود في AuctionCommand
   (MAX_SEARCH_RESULTS=15، أرخص أولاً، LIKE مهرّب). أضيف لجدولي الأوامر.
10. **أرقام قديمة**: 120+ عنصر → **185 فعلياً** (11 قسم ✓)؛ AuctionManager
    954 → 2,570 سطر؛ SellScreenHandler 746 → 825؛ صفحات المزاد 45 → **42**
    (الف giovanni 48/50/53 محجوزة)؛ إصدار header 2.0.0 → 2.1.4.
11. **تسلسل التهيئة §4** — أُعيد بناؤه من SolidusMod.java: SolidusAPI يُهيأ
    وقت init وليس SERVER_STARTED (إصلاح توافق Governance)، ChatPrompts +
    TradeManager، ثريد الإيقاف يبدأ بـtradeManager.shutdown()، TICK يعيد
    أيضاً reapIdleSessions، DISCONNECT يلغي الجلسات والمطالبات.
12. **TransferResult** كان موثقاً بحقلين والفعلي 4 حقول؛ **transferOffline**
    كانت موثقة "deduct-then-add مع استرجاع يدوي" والفعلي معاملة واحدة
    `transferAtomic` (لا يوجد مسار استرجاع يدوي أصلاً).
13. **الإشعارات غير المتصلة** — مخطط §6.4 يعرض خريطة in-memory أُزيلت منذ
    "FIX v2"؛ الفعلي: قاعدة بيانات فقط مع حذف انتقائي بالمعرفات.
14. **VERSIONING.md** — "Current family 2.1.0" → 2.1.4 + مثال suggests
    صُحح للمفاتيح الحقيقية في fabric.mod.json.
15. **متطلبات التشغيل** — Loader 0.19.2+/API 0.149.0+ → **0.19.4+ / 0.155.2+**
    (من fabric.mod.json وgradle.properties، المتزامن مع باقي العائلة).
16. **MONEY_ROUNDING.md** — ذكرت `TaxEngine.roundTax` كأنه في هذا المستودع؛
    هو في Solidus-Governance (مستودع منفصل). وُضّح.

## 4) ما تم التحقق منه وكان صحيحاً (عينات)

- نموذج escrow كاملاً: 3 أطوار في placeBid، claim شرطي
  `WHERE current_bid IS NULL OR current_bid < ?`، سواتب الإقلاع
  (refundOrphanedBidStates + checkEscrowConsistency بعتبة 0.01)، كل مسارات
  الاسترداد في FEATURES §1.4 ✓.
- ثوابت BidRules (5%/1$، نافذة 10د، تمديد 5د، سقف 12) ✓.
- التداول: 10 كتل، TTL 30ث، cooldown 5ث، idle 15د، لفات مال متسلسلة مع
  rollback للفة الأولى، أرصدة عبر transferOffline (تشتغل hooks) ✓.
- ChatPrompts: TTL 5 دقائق، ALLOW_CHAT_MESSAGE مع إلغاء البث، كلمة
  cancel/الغاء، إزالة الفواصل ✓.
- الـ309 اختبارات ✓، شجرات جداول economy.db/auctions.db الأساسية ✓،
  RateLimiter 150ms/1000ms/5min ✓، Trading hooks عبر allowTransfer فقط (لا
  hooks جديدة) ✓، shulker في مسارات /sell ✓.

## 5) نقاط للقرار لاحقاً (لا تؤثر على صحة التوثيق الحالي)

1. README يذكر "Fabric, Forge (via protocol translation)" في Key Decisions —
   بقاء هذه الجملة تقديري؛ الكود server-side صرف ولا شيء يمنعها لكنه غير
   مُختبر هنا. أبقيتها كونها وصفاً للتوافق النظري لا ميزة كود.
2. notes/CHANGES.md (سجل 2.1.0 التاريخي) يذكر حذف ScreenHandlerMixin ضمنية
   عبر "Mixin testing" — أبقيته كسجل تاريخي دون تعديل.
3. إذا أردت لاحقاً فعلاً حقيقياً لـanti-farm (تخفيض تلقائي بالنسبة)، نقطة
   التنفيذ الطبيعية هي `ShopManager.parseItem` بإضافة مفتاح
   `sell-price-reduction` اختياري.

— نهاية تقرير التدقيق —
