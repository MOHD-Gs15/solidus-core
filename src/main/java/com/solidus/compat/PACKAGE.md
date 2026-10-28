# The compat package — Minecraft-update isolation layer

> **المرجع السريع عند صدور إصدار Minecraft جديد.**
> هذه الحزمة هي *العقد الوحيد* بين Solidus ودواخل Minecraft (internals)
> المتغيّرة بين الإصدارات. عند تحديث MC، التغييرات يجب أن تنحصر داخل
> الملفات المذكورة هنا — لا شيء آخر في الـ 29,500 سطر يُلمس.

Family 2.3.0 (audit W-3 / W-5). English summary at the bottom.

---

## بنية الحزمة

```
com/solidus/compat/
├── Compat.java                 ← الواجهة العامة الوحيدة (facade) — يختار النسخة النشطة
├── CompatState.java            ← حالة التوافق: أعلام المتاحة + أسباب الفشل (لا تعتمد على MC)
├── CompatProbes.java           ← فحص الإقلاع الانعكاسي لكل الأشكال التي بنينا ضدها
├── SolidusMixinPlugin.java     ← قناة تأكيد تطبيق الـ mixin (postApply) عبر IMixinConfigPlugin
├── ClickDescriptor.java        ← تمثيل النقرة المستقر (بدون أي نوع من MC)
├── ClickInputResolver.java     ← الواجهة المستقرة لتحويل مدخلات النقر
├── ContainerClickBridge.java  ← الواجهة المستقرة لتوجيه نقرات الحاويات
├── Palette.java               ← لوحة ألوان الـGUI بمفاتيح registry id (بلا ثوابت MC ملونة) — WS-1
├── CompatDrop.java            ← الإسقاط الأرضي version-free (استراتيجية Prediction/2-arg) — WS-1
└── impl_26_1/
    └── ContainerClickBridge_26_1.java   ← كل معرفة 26.1.x (accessors + ContainerInput)
```

## قائمة الفحص عند تحديث Minecraft (مثال: 26.1 → 26.2)

0. **`CompatArchitectureGuardTest`** (WS-1): شغّل هذا الاختبار **قبل أي شيء آخر** —
   إن أخضر فهذا يعني أن الشجرة ما زالت نظيفة (صفر ثوابت ملونة، صفر `.drop(` خام
   خارج `com.solidus.compat`)، وأي انحدار يُكشف هنا بدل السجل.
1. **`gradle.properties`**: `minecraft_version`، `fabric_api_version`، و`minecraft_dep`.
2. **`impl_26_2/`** (جديدة): انسخ `ContainerClickBridge_26_1` وعدّل الأشكال المتغيرة
   (accessors الحزمة، نوع مدخل النقر بدل `ContainerInput`، إلخ).
3. **`Compat.verifyCompatibility()`**: أضف النسخة الجديدة لاختيار الجسر
   (ابدأ بالأحدث؛ التراجع للقديم تلقائي عند فشل المسبارات).
4. **`CompatProbes`**: حدّث أسماء/أشكال الأهداف (مثل تغيّر `ContainerInput`
   إلى نوع جديد، أو تغيّر توقيع `handleContainerClick`).
5. **`ContainerClickMixin`**: تحقّق من اسم الدالة المستهدف فقط
   (`method = "handleContainerClick"`) — لا منطق هنا يُلمس.
6. **`solidus.mixins.json`**: لا يغيّر عادةً.
7. **توقيع override في الـ ScreenHandlers**: `clicked(int, int, <InputType>, Player)`
   هو عقد vanilla dispatch (خط الدفاع الأول). إن غيّرت Mojang التوقيع
   يجب تحديثه في الملفات الأربعة — وهذا *الاستثناء الوحيد* خارج الحزمة،
   لأن Java تفرض مطابقة توقيع الوراثة. المنطق نفسه لا يُلمس.

## لماذا require = 0 على الـ mixin عالي الخطورة؟

سابقاً: `defaultRequire = 1` يعني أن أي تغيير شكل في `handleContainerClick`
من Mojang = **انهيار كامل عند الإقلاع** لكل خادم حدّث MC قبل صدور تحديث Solidus.

الآن: الفشل يتحوّل إلى **تحذير في السجل + تعطيل مقنّن**:

1. `require = 0` على الـ `@Inject` → لا انهيار.
2. `CompatProbes.verifyCompatibility()` عند التهيئة تتحقق انعكاسياً من كل شكل
   (اسم الدالة، accessors الحزمة، ثوابت `ContainerInput`، توقيع `clicked`،
   `broadcastFullState`، حقول `@Shadow`).
3. عند أي فشل: **كل مسارات فتح الـ GUI تُغلق برسالة واضحة** — بينما
   `/pay` و`/balance` و`/baltop` والتخزين والـ ledger والضرائب
   ومسارات فرض القواعد كلها تعمل كالمعتاد.
4. عند أول اتصال لاعب: `Compat.confirmClickMixinAfterFirstConnection()`
   يتحقق أن الـ mixin طُبّق فعلاً (عبر `SolidusMixinPlugin.postApply`) —
   شبكة أمان ضد التداخل مع transformers لماودات أخرى.

## ترتيب "التبعيات الهشة مقابل النظيفة"

| نظيفة (نادراً تكسر) | هشة (تكسر كل تحديث — محصورة في compat) |
|---|---|
| `EconomyEngine`, `BalanceManager` | الـ mixin على `handleContainerClick` |
| `TransactionLog` | الـ mixins على `ServerPlayer.hurtServer`/`die` (Enforcer) |
| التخزين (SQLite/MySQL) | `fabric.mod.json` version pinning (`minecraft_dep`) |
| الـ ledger والـ cryptography | أي ربط بـ `ContainerInput` أو packet records |
| `Palette` (مفاتيح registry id نصية) | ثوابت `Items.*`/`Blocks.*` الملونة (اختفت في 26.3) |
| `CompatDrop` (واصفان معروفان + MethodHandle) | توقيع `Player/ServerPlayer.drop` (تغيّر في 26.3) |

### القاعدة المستفادة من WS-1

> **الثوابت تتغيّر، المعرّفات تبقى.** عند أي تحديث MC: فضّل الحلّ عبر
> `BuiltInRegistries` بمفتاح نصي على الإحالة إلى ثوابت `Items`/`Blocks`.
> في 26.3 حذف Mojang الثوابت الملونة من `Items` **و**`Blocks` (بقي ثابت عام واحد)
> بينما بقيت registry IDs — فصار الملف الوحيد الذي يعرف الألوان هو `Palette`.

> **The constants change, the ids stay.** On every Minecraft upgrade,
> prefer resolving through `BuiltInRegistries` with a string id over
> referencing `Items`/`Blocks` constants. In 26.3 Mojang removed the
> colored constants from `Items` AND `Blocks` (one generic constant
> survived) while the registry ids all remained — making `Palette` the
> only file in the codebase that knows the colors. For world drops, the
> same lesson applies to signatures: `CompatDrop` owns the two measured
> shapes (26.1.x 2-arg, 26.3 `Prediction` 3-arg) so no call site ever
> re-learns them.

---

## English summary

**This package is the ONLY place allowed to know Minecraft internals that
churn between versions.** `CompatProbes` verifies at startup (reflection over
the unobfuscated 26.1+ runtime names) that every shape we compiled against
still exists; `CompatState` turns the report into capability flags; every
virtual-GUI open path calls `Compat.ensureGuiAvailable(player)` so a broken
surface produces a clear message while commands/storage/ledger keep working.
The 26.1-specific knowledge lives exclusively in `impl_26_1/`. The checklist
above is the complete Minecraft-update procedure — everything else in the
codebase is deliberately version-free.

**Since 2.4.0 (WS-1)**, two more surfaces joined the isolation boundary:
`Palette` renders every GUI color from registry ids (the colored `Items.*`
constants were removed in 26.3 — ids survived), and `CompatDrop` wraps the
world-drop call sites behind a dual-strategy MethodHandle (26.3's
`ServerPlayer#drop(ItemStack, boolean, Prediction)` with `SERVER_ONLY`,
26.1.x's `Player#drop(ItemStack, boolean)`), failing closed with a CRITICAL
log + persistent alternative instead of silently destroying items.
`CompatArchitectureGuardTest` enforces both boundaries at build time.
