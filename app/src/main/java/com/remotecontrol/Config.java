package com.remotecontrol;

/**
 * Config — единственное место для настройки сервера.
 * Замени BASE_URL на актуальный адрес из bore.
 */
public final class Config {
    private Config() {}

    /**
     * Адрес твоего HTTP-сервера через bore.
     * Пример: "http://bore.pub:56485"
     */
    public static final String BASE_URL = "http://bore.pub:56485";

    /** Эндпоинты */
    public static final String ENDPOINT_GET_COMMAND = BASE_URL + "/get_command";
    public static final String ENDPOINT_UPLOAD      = BASE_URL + "/upload";
}
