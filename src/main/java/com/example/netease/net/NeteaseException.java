package com.example.netease.net;

/**
 * 网络层统一受检异常。
 *
 * <p>所有对外方法只抛这一种异常（或不抛，按各自签名注释降级为 null）；异常消息中
 * 绝不允许出现 cookie / 密码 / encSecKey 明文。</p>
 */
public class NeteaseException extends Exception {

    private static final long serialVersionUID = 1L;

    public NeteaseException(String msg) {
        super(msg);
    }

    public NeteaseException(String msg, Throwable c) {
        super(msg, c);
    }
}
