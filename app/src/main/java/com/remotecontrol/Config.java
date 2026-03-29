package com.remotecontrol;

/**
 * Config
 *
 * ╔══════════════════════════════════════════════════════╗
 * ║  ВСТАВЬ СВОИ ЗНАЧЕНИЯ СЮДА (или через BuildConfig)  ║
 * ╚══════════════════════════════════════════════════════╝
 *
 * Рекомендуется передавать через gradle.properties / secrets:
 *   BOT_TOKEN = buildConfigField в build.gradle
 */
public final class Config {

    private Config() {}

    /**
     * Токен вашего Telegram бота от @BotFather.
     * Формат: "1234567890:ABCDEFabcdef..."
     *
     * ⚠️  НЕ коммитьте реальный токен в репозиторий!
     * Используйте GitHub Secrets + BuildConfig (см. build.gradle).
     */
    public static final String BOT_TOKEN = BuildConfig.BOT_TOKEN;

    /**
     * URL вашего Telegram Mini App на GitHub Pages.
     * Пример: "https://username.github.io/repo-name/miniapp/"
     *
     * Mini App должен быть задеплоен через .github/workflows/build.yml
     * в ветку gh-pages или папку docs/.
     */
    public static final String MINI_APP_URL = BuildConfig.MINI_APP_URL;
}
