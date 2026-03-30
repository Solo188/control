package com.remotecontrol;

public final class Config {
    private Config() {}

    // ЗАМЕНИ ПОРТ НА ТОТ, ЧТО В BORE ПРЯМО СЕЙЧАС
    public static final String BASE_URL = "http://bore.pub:43367";

    public static final String ENDPOINT_GET_COMMAND = BASE_URL + "/get_command";
    public static final String ENDPOINT_UPLOAD      = BASE_URL + "/upload";
    public static final String ENDPOINT_ACK         = BASE_URL + "/ack";
}
