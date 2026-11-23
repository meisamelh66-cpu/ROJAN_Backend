package ai.rojan.backend.domain.salon

/** One suggested [ServiceCategory] name plus the service names commonly offered under it. */
data class DefaultServiceCategoryEntry(val name: String, val serviceTypes: List<String>)

/**
 * Default Beauty Salon Category Catalog - a fixed set of category/service-
 * type suggestions for a beauty salon, shipped as static backend data (the
 * "Backend/default seed data" source of truth) rather than Android-hardcoded
 * strings or real per-salon [ServiceCategory] rows. Nothing here is ever
 * persisted on its own: a salon only gets a real, owned [ServiceCategory]
 * when it actually creates one (via [ServiceCategoryRepository.save],
 * reachable through the existing `POST /salons/{salonId}/categories`),
 * whether by picking a suggestion from this list or typing a custom name.
 * That's what keeps this catalog from silently seeding hundreds of active
 * services per salon - it is suggestions only, never auto-assigned.
 */
object DefaultServiceCatalog {
    val entries: List<DefaultServiceCategoryEntry> = listOf(
        DefaultServiceCategoryEntry(
            "مو و کوتاهی",
            listOf(
                "کوتاهی مو", "کوتاهی کودک", "کوتاهی مردانه", "اصلاح و فرم‌دهی مو", "خرد کردن مو",
                "چتری", "براشینگ", "سشوار", "شینیون", "بافت مو", "مدل مو",
            ),
        ),
        DefaultServiceCategoryEntry(
            "رنگ و مش",
            listOf(
                "رنگ مو", "رنگ ریشه", "رنگساژ", "مش", "هایلایت", "لولایت", "بالیاژ", "آمبره",
                "سامبره", "دکلره", "ریموو رنگ", "اصلاح رنگ مو", "فیس‌فریم", "پوشش سفیدی",
            ),
        ),
        DefaultServiceCategoryEntry(
            "احیا و مراقبت مو",
            listOf(
                "کراتین مو", "پروتئین‌تراپی", "بوتاکس مو", "پلکس‌تراپی", "احیای مو", "آبرسانی مو",
                "ویتامینه مو", "تراپی مو", "درمان خشکی مو", "درمان موهای آسیب‌دیده",
            ),
        ),
        DefaultServiceCategoryEntry(
            "ناخن",
            listOf(
                "مانیکور", "پدیکور", "ژل پولیش", "لاک ژل", "کاشت ناخن", "کاشت ژل", "کاشت پودر",
                "لمینت ناخن", "ژل بیلدر", "ترمیم ناخن", "طراحی ناخن", "فرنچ", "ریموو کاشت", "ریموو ژل",
            ),
        ),
        DefaultServiceCategoryEntry(
            "ابرو",
            listOf(
                "اصلاح ابرو", "قرینه‌سازی ابرو", "طراحی ابرو", "رنگ ابرو", "لیفت ابرو", "لمینت ابرو", "هاشور ابرو",
            ),
        ),
        DefaultServiceCategoryEntry(
            "مژه",
            listOf(
                "اکستنشن مژه", "ترمیم اکستنشن مژه", "لیفت مژه", "لمینت مژه", "رنگ مژه", "ریموو اکستنشن مژه",
            ),
        ),
        DefaultServiceCategoryEntry(
            "میکاپ و گریم",
            listOf(
                "میکاپ", "میکاپ عروس", "میکاپ مهمانی", "میکاپ مجلسی", "گریم", "گریم عروس",
                "میکاپ روزانه", "میکاپ لایت", "میکاپ اروپایی",
            ),
        ),
        DefaultServiceCategoryEntry(
            "پوست و فیشال",
            listOf(
                "فیشال", "پاکسازی پوست", "پاکسازی عمیق", "آبرسانی پوست", "ماسک صورت", "لایه‌برداری",
                "اسکین‌کر", "مراقبت پوست", "فیشال تخصصی",
            ),
        ),
        DefaultServiceCategoryEntry(
            "اصلاح و اپیلاسیون",
            listOf(
                "اصلاح صورت", "اصلاح بدن", "بند انداختن", "وکس صورت", "وکس بدن", "اپیلاسیون",
                "اصلاح ابرو", "اصلاح پشت لب", "اصلاح چانه",
            ),
        ),
        DefaultServiceCategoryEntry(
            "عروس",
            listOf(
                "پکیج عروس", "میکاپ عروس", "شینیون عروس", "خدمات موی عروس", "خدمات ناخن عروس",
                "خدمات ابروی عروس", "خدمات مژه عروس", "آماده‌سازی عروس",
            ),
        ),
        DefaultServiceCategoryEntry(
            "شینیون و استایل مو",
            listOf(
                "شینیون کلاسیک", "شینیون باز", "شینیون بسته", "شینیون عروس", "بافت مو", "استایل مو",
                "فر مو", "حالت‌دهی مو",
            ),
        ),
        DefaultServiceCategoryEntry(
            "خدمات مردانه",
            listOf(
                "اصلاح مو", "کوتاهی مردانه", "اصلاح ریش", "فرم‌دهی ریش", "رنگ مو مردانه", "رنگ ریش",
                "پاکسازی پوست مردانه", "گریم مردانه",
            ),
        ),
        DefaultServiceCategoryEntry(
            "خدمات کودک",
            listOf("کوتاهی کودک", "بافت کودک", "مدل مو کودک", "خدمات ناخن کودک"),
        ),
        DefaultServiceCategoryEntry(
            "خدمات بدن",
            listOf(
                "اسکراب بدن", "مراقبت بدن", "ماساژ", "ماساژ ریلکسی", "ماساژ صورت", "مراقبت دست", "مراقبت پا",
            ),
        ),
        DefaultServiceCategoryEntry(
            "خدمات تخصصی زیبایی",
            listOf(
                "خدمات تخصصی پوست", "خدمات تخصصی مو", "خدمات تخصصی ابرو", "خدمات تخصصی مژه", "خدمات تخصصی ناخن",
            ),
        ),
    )
}
