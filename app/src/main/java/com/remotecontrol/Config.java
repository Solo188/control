package com.remotecontrol;

public final class Config {
    private Config() {}

    // Твой адрес из bore (обязательно с http://)
    public static final String SERVER_URL = "http://bore.pub:56485";
    
    // Заглушки, чтобы MainActivity не выдавала ошибки
    public static final String BOT_TOKEN = "bore_mode";
    public static final String MINI_APP_URL = "http://github.io"; 
}
