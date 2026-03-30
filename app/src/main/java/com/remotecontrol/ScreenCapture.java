package com.remotecontrol;

/**
 * ScreenCapture — утилитный класс (ранее содержал мёртвый дублирующий код).
 *
 * ИСПРАВЛЕНО:
 * - Убран мёртвый метод sendScreenshot() с "http://YOUR_SERVER_URL"
 * - Логика захвата полностью в ScreenCaptureRequestActivity
 * - Этот класс можно использовать как точку входа для запроса захвата экрана
 */
public final class ScreenCapture {

    private ScreenCapture() {}

    /**
     * Запросить захват экрана для указанной команды.
     * Запускает ScreenCaptureRequestActivity, которая покажет
     * системный диалог подтверждения MediaProjection.
     *
     * @param commandId ID команды, для которой нужен скриншот
     */
    public static void capture(int commandId) {
        TelegramService svc = TelegramService.getInstance();
        if (svc == null) return;

        ScreenCaptureRequestActivity.request(svc, commandId);
    }
}
